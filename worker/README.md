# worker — Backtest Worker

## NautilusTrader 모멘텀 엔진 (Jira 101)

Python 3.11, NautilusTrader **1.219.0**, yfinance **0.2.65**. 엔진 실행과 결과 계산만
담당하며 브로커·SSE·DB는 앞으로 연결할 Consumer가 담당한다. 기존 `worker.main`과
축약 `run_backtest`는 이 경로에 연결되지 않은 Kafka 레거시 스캐폴딩이다. 브로커는 아직
RabbitMQ/Kafka 중 최종 결정되지 않았다.

Python/Nautilus가 낯설다면 [Java 개발자용 엔진 가이드](../docs/guides/nautilus-momentum-engine.md)를
먼저 읽는다. 설계 이유는 [ADR-0003](../docs/adr/0003-nautilus-momentum-engine-boundary.md),
필드별 계약은 [Jira 101 spec](../docs/specs/101-nautilus-momentum.md)이 기준이다.

### 설치와 실행

저장소 루트에서:

```bash
cd worker
uv sync --python 3.11 --group dev
uv run python -m worker.demo --input examples/momentum-demo-input.json --output engine-output.json
```

마지막 명령 하나가 **실제 데이터 조회 → Nautilus 실행 → 결과 JSON 2개 저장**을 수행한다.
파일은 성격에 따라 나뉜다.

- `engine-output.json` — 메시지 계약 `EngineOutput`. 브로커로 나갈 payload 그 자체다.
- `engine-output-artifacts.json` — 로컬 진단 산출물 `RunArtifacts`(주문 원장 `orders`,
  재현성 `metadata`). 메시지 계약에 없으므로 브로커로 나가지 않는다. 경로는 `--output`
  옆에 `-artifacts`를 붙여 자동으로 정하며 `--artifacts`로 직접 지정할 수 있다.

예제 입력은 실제 검증에 성공한 2020-09-18~2022-12-30 구간이다. 인자 없이 실행하면
최근 완료 거래일을 끝으로 약 5년을 요청한다(서울 16시 이전에는 전날까지).

```bash
uv run python -m worker.demo --output engine-output.json
uv run python -m worker.demo --ticker 000660.KS --start 2020-09-18 --end 2022-12-30
```

다른 코스피 종목을 사용하려면 `--ticker 005930.KS`처럼 명시한다. 자동 종목 대체는 없다.
`--input`을 쓰면 입력 JSON의 실행 조건이 CLI 조건보다 우선한다. `run.end`는 포함이고
yfinance의 배타적 end에는 하루를 더한다. 실제 평가 시작·종료일과 종목명은 산출물 파일의
metadata에 기록한다.

네트워크 없는 작은 테스트 데이터(실제 시세가 아님):

```bash
uv run python -m worker.demo --fixture examples/momentum_fixture.csv --start 2025-01-06 --end 2025-01-14 --lookback-days 2 --output examples/fixture-output.json
```

`examples/fixture-output.json`(메시지)과 `examples/fixture-output-artifacts.json`(진단
산출물)이 함께 생성된다. 작은 파일의 신호/청산 검증용 lookback은 2이다. 실제 전략의 기본값과 별도 경계 테스트는 120이다.
uv 없이 설치하려면 `python -m pip install -e . -r requirements-dev.txt`를 사용할 수 있다.

### Consumer 연결

```python
from pathlib import Path

from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import EngineOutput
from worker.contracts.events import ProgressPayload, ProgressPoint
from worker.engine_runner import NautilusMomentumEngine

buffer: list[ProgressPoint] = []

def on_progress(point: ProgressPoint, done: int, total: int) -> None:
    buffer.append(point)
    if len(buffer) == 20 or done == total:
        payload = ProgressPayload(
            completed_trading_days=done,
            total_trading_days=total,
            portfolio_series=list(buffer),
        )
        # Consumer가 payload를 발행한다. 발행 성공 후 buffer.clear().
        buffer.clear()

engine = NautilusMomentumEngine()  # 공급자를 생략하면 yfinance
engine_input = EngineInput.model_validate_json(
    Path("examples/momentum-demo-input.json").read_text(encoding="utf-8")
)
result: EngineOutput = engine.run(engine_input, on_progress)
result_json = result.to_json()
```

- 공개 실행 경로는 `NautilusMomentumEngine.run(engine_input, on_progress) -> EngineOutput`.
  dict 입력도 가능하다. 시장 데이터 공급자는 실행 인자가 아니라 생성자 인자
  `NautilusMomentumEngine(data_provider)`이므로 엔진 인스턴스를 여러 실행에서 재사용한다.
  커스텀 공급자는 `load(EngineInput) -> MarketDataset`를 구현한다.
- 입력·결과·진행 모델 모두 공용 메시지 계약이다(`contracts/engine_input.py`,
  `contracts/engine_output.py`, `contracts/events.py`). 과도기 입력 모델
  `engine_contracts.py`는 제거했다 — 계약에 거래비용 모델 `ZERO`를 추가하고
  ([ADR-0004](../docs/adr/0004-backtest-commission-model.md)) 데모 고정값을 `model=ZERO`로
  바꾼 결정, `delisting=CAUSE_BASED`를 받고 경고로 남기는 결정
  ([ADR-0003](../docs/adr/0003-nautilus-momentum-engine-boundary.md) "D-상장폐지")으로
  마지막 두 차이가 해소됐다. `marketData.adjustment`는 공급자를 `auto_adjust=False`로 바꿔
  계약 고정값 `SPLIT_ADJUSTED`와 맞췄다.
- **계약은 통과하지만 이 엔진이 계산할 수 없는 요청은 실행 전에 거절한다**
  (`worker/engine_support.py`, `run.error.code=INVALID_REQUEST`). 계약(`contracts/`)은 백엔드와
  공유하며 "메시지만 보고 판단할 수 있는" 불변식만 보므로, 지원 범위 판단은 엔진 몫이다.
  거절 대상: 종목 2개 이상, `portfolio.leverage != 1`, `shortSelling=true`,
  `currency != KRW`, `execution.commission.model != ZERO`, `execution.slippage.bps != 5`,
  `partialFill=true`, `benchmark` 지정, 그리고 엔진 레지스트리(`strategies/__init__.py`
  `STRATEGIES`)에 없는 `strategy.strategyId`·`strategy.version`. 여러 항목이 동시에 미지원이면
  한 메시지에 전부 담는다. 지원하지 않는 값을 조용히 무시하고 `COMPLETED`를 반환하지 않는다 —
  요청한 정책과 다른 방식으로 계산한 숫자가 그 정책의 결과로 저장되기 때문이다.
- `execution.delisting=CAUSE_BASED`는 거절하지 않고 받되, 사유 분류 미지원을
  `diagnostics.warnings`에 `DELISTING_CLASSIFICATION_UNSUPPORTED`로 **실행당 1건** 남긴다.
  이 경고는 특정 거래일이 아니라 실행 전체에 걸리므로 계약 필수 필드인 `date`에 평가 구간의
  첫 거래일을 넣는다.
- `run.randomSeed`는 계약상 생략 가능하다. 생략하면 재현성을 위해 고정 기본값 `42`를 쓰고
  실제 사용값을 진단 산출물 `metadata.randomSeed`에 남긴다.
  `portfolio.positionSizing.maxPositionPct` 생략은 "제한 없음"(= 1)으로 해석한다.
- 콜백 규약은 계약 `contracts.engine.ProgressCallback`이다: 평가 거래일당 1번
  `(point, completed_trading_days, total_trading_days)`를 전달하며 준비 데이터는 제외한다.
  `point`는 `ProgressPoint(date, equity, cumulativeReturn, drawdown)`이고 최종 결과의 같은 날짜
  `portfolioSeries` 항목과 값이 일치한다. 20거래일 묶음 발행·벤치마크 계산은 Consumer 책임이다.
  진행 이벤트는 저장하지 않고 SSE로만 흐르므로 보유 종목까지 담은 전체 스냅샷은 넘기지 않는다.
  콜백에 넘어가는 객체는 결과와 분리되어 있어 Consumer의 변경이 최종 결과를 오염시키지 않는다.
- 금액·비율·수량은 JSON decimal 문자열, 주문/체결/거래 건수는 정수다.
  계산 불가능한 지표와 OPEN 거래의 미실현 필드는 값이 없는 상태이며, 계약 직렬화
  `ContractModel.to_json()`(= camelCase + `exclude_none`)은 이런 키를 아예 내보내지 않는다.
- `portfolioSeries`는 매일 존재한다. `positions`는 첫날·마지막 날·수량 변경일에만 존재하며,
  생략은 구성 스냅샷 없음, 빈 배열은 현금 상태를 뜻한다.
- 데이터/엔진 오류는 `run.status=FAILED`, `run.error={code, message}`로 반환하고
  `performance`·`execution`은 채우지 않는다(메시지에서는 키가 빠진다).
  계약 자체를 위반한 EngineInput은 예외(pydantic `ValidationError`)지만, 계약은 지켰으나 엔진이
  지원하지 않는 값은 예외가 아니라 `FAILED` + `INVALID_REQUEST` 결과로 돌아온다.
  콜백 실패는 CallbackError를 발생시킨다.
  Consumer는 결과가 COMPLETED인지 확인해야 하며, 콜백 100%가 저장 성공을 뜻하지 않는다.
- 주문 원장과 재현성 metadata는 메시지 계약에 없다. 필요하면 `run(...)` 대신
  `NautilusMomentumEngine.run_with_artifacts(...) -> (EngineOutput, RunArtifacts)`를 쓴다.
  `run(...)`은 계약 메시지만 반환한다. 산출물은 반환값으로만 전달하고 엔진 인스턴스에
  남기지 않으므로 같은 인스턴스로 여러 번 실행해도 결과가 섞이지 않는다.

### 체결·계산 규칙

종가로 주문 객체와 수량을 생성하고 다음 거래일 시가 이벤트에서 제출한다. `bar_execution=False`
이며 시가 QuoteTick의 bid/ask에 각각 -/+5bps를 적용한다. 실제 체결은 Nautilus가 수행하며
native 체결 건수와 native 잔액이 출력 원장과 일치하는지도 검사한다. 실제 시가 비용이 현금을
초과하면 **제출 게이트웨이에서 주문 전체를 거절**하고 INSUFFICIENT_CASH 경고를 남긴다.
거절은 진단 산출물의 주문 원장과 메시지의 `execution.rejectedOrderCount`에 기록되며,
거래소에 제출되지 않았으므로 native 주문 캐시에는 없다.

수수료·세금 0, 슬리피지 5bps, 정수 수량, 추가 매수·일별 리밸런싱·공매도·강제 청산 없음.
마지막 종가 주문은 PENDING이며 다음 평가 거래일이 없어 제출하지 않는다. 호가 수량은
Nautilus의 최대 표현 수량을 사용하여 모든 지원 가능한 주문을 전량 체결한다(실제 거래량 무관).

일별 `cash + positionValue`로 평가한다. Sharpe는 252일, RF=0, ddof=1, 첫날 수익률 0이며
CAGR은 실제 첫/마지막 평가 거래일 사이 달력 일수를 사용한다. MDD는 음수다. CLOSED만
tradeCount/승률에 포함한다. entryPrice/exitPrice는 실제 체결가다. grossPnl은 슬리피지 전
시가(기준가) 손익, realizedPnl은 실제 체결가 손익이므로 비용을 중복 차감하지 않는다
(`realizedPnl == grossPnl - commission - slippageCost`). 기준가 자체는 엔진 내부 값이며
메시지에는 없다. 실제 체결가와 slippageCost로 역산되고, 주문별 기준가는 진단 산출물의
주문 원장(`referencePrice`)에 남는다.

자세한 계약 해석은 [설계 결정](../docs/specs/101-nautilus-momentum.md)을 참고한다.

### 검증 결과와 제약

**회귀 기준(regression baseline)은 고정 데이터 실행값이다.** `examples/momentum_fixture.csv`,
`lookbackDays=2`, 2025-01-06~2025-01-14, 초기 자금 1,000으로 실행하면 준비 **2거래일**,
평가 **7거래일**, 주문 **3건**(모두 체결, 거절 0건), CLOSED **1건** + OPEN **1건**,
최종 자산 **1068.7**이 나온다. `tests/test_momentum_engine.py`가 평가 거래일 수, 주문 건수와
체결가, 손익 항등식, 최종 자산을 직접 단언하므로 네트워크 없이 재현되고 실제로 테스트가
검증한다. 다음 날 체결, 미래 시가와 무관한 수량,
중복 주문 방지, 잔여 현금, 전량 거절 후 재평가, 양방향 슬리피지, OPEN/CLOSED, 지표, 콜백도
같은 파일에서 pytest로 검증한다.

**라이브(yfinance) 실행값은 참고용이며 재현되지 않는다.** 아래 관측은 실행 성공 여부와
공급자 특성에 대한 기록이고, 성과 숫자는 기준값이 아니다.

- 실제 SK하이닉스 **000660.KS / SK hynix Inc. / KRW / KSC** 확인 후 2020-09-18~2022-12-30
  요청 성공. 실제 평가 종료는 2022-12-29, 평가 **561거래일**, 준비 **249거래일**.
- **이전에 이 문서에 적어 두었던 라이브 성과·체결 건수 기록은 보정 방식을
  `auto_adjust=False`(`SPLIT_ADJUSTED`)로 바꾼 뒤로 무효다.** 배당 재투자가 빠져 수익률이
  낮아지고, 초기자본이 고정된 상태에서 가격이 달라지면 매수 가능 주식 수(정수)가 바뀌므로
  체결·거절 건수까지 달라진다. 라이브 값은 애초에 재현되지 않으므로 새 숫자를 기록하지
  않는다. 필요하면 직접 실행해 확인한다.
- 이전 `auto_adjust=True` 기록에서는 **같은 명령을 몇 분 뒤 다시 실행하면 최종 자산과
  산출물 `metadata.dataHash`가 달라졌다**(2026-09-07·09-08 관측). 체결·거절 건수와 평가
  거래일 수는 같았으므로 엔진 계산이 바뀐 것이 아니라 **공급자가 과거 시세를 계속 정정하는
  것**이었다. 배당 보정은 현재 시점 기준으로 과거를 역산하므로 새 배당마다 과거 가격이 다시
  계산된다. 분할만 반영하면 그만큼 값이 안정적이다. 라이브 실행에는 네트워크가 필요하다.
- 최근 약 5년 요청(2021-09-06~2026-09-06)은 **실패**했다. Yahoo가 2025-09-19에 모든 OHLC를
  NaN으로 반환했다. 다른 5년 구간에서는 2023-02-02, 2023-02-09, 2024-10-14의 OHLC 범위
  오류도 확인했다. 이를 임의 수정·삭제하지 않고 날짜를 포함한 오류를 반환한다.
  기본 5년 실행 성공을 보장하지 않으며, 공급자 데이터 정정 또는 다른 명시적 기간이 필요하다.
- 조정 방식은 yfinance `auto_adjust=False`로 액면분할만 반영한 원본 시계열을 쓴다
  (= 메시지 계약 고정값 `SPLIT_ADJUSTED`). 응답에 `Adj Close` 컬럼이 함께 오지만 검증 단계에서
  OHLCV만 사용한다. 배당 재투자는 반영하지 않는다(팀 승인). 근거는
  [ADR-0003](../docs/adr/0003-nautilus-momentum-engine-boundary.md) "A-보정방식"에 있다. 실제 KRX 틱 규칙,
  기업행동/거래정지/상장폐지 원인 분류는 구현하지 않는다. 실제 원화의 정수 단위 반올림을 피하기
  위해 native 계정에는 `KRW_SIM`(KRW와 1:1, 8자리)을 사용하고 출력은 KRW로 표시한다.
  가격은 6자리로 정규화한다. 실거래용 브로커 모델이 아니다.
- yfinance는 과거 시세가 실제로 정정되는 임시 공급자다(위 dataHash 변화가 그 관측 결과다).
  dataHash/버전/실제 기간을 기록하지만 장기 재현을 위해서는 향후 원시 데이터 스냅샷 보관
  공급자로 교체해야 한다. 그때까지 라이브 실행값을 회귀 기준으로 쓰지 않는다.

필수 검증:

```bash
uv run ruff check src tests
uv run ruff format --check src tests
uv run pytest
```

## 기존 Consumer 스캐폴딩

Kafka `backtest.jobs` 토픽에서 Job을 받아 백테스트를 실행하고 결과를 PostgreSQL에 저장한다.
같은 코드를 여러 개 실행해(Consumer Group) 분산 처리한다.

```bash
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt -r requirements-dev.txt
python -m worker.main
```

- lint/format: `ruff check src tests` / `ruff format src tests`
- test: `pytest`
