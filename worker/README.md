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
uv run python -m worker.demo --input examples/engine-input.json --output engine-output.json
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
from worker.engine import run_engine
from worker.models import EngineInput, EngineOutput, ProgressSnapshot

buffer = []

def on_snapshot(progress: ProgressSnapshot) -> None:
    buffer.extend(progress.portfolio_series)
    done, total = progress.completed_trading_days, progress.total_trading_days
    if len(buffer) == 20 or done == total:
        payload = {
            "completedTradingDays": done,
            "totalTradingDays": total,
            "portfolioSeries": [p.model_dump(mode="json", by_alias=True) for p in buffer],
        }
        # Consumer가 payload를 발행한다. 발행 성공 후 buffer.clear().
        buffer.clear()

engine_input = EngineInput.model_validate_json(open("examples/engine-input.json").read())
result: EngineOutput = run_engine(engine_input, on_snapshot=on_snapshot)
result_json = result.model_dump_json(by_alias=True)
```

- `run_engine(engine_input, *, data_provider=None, on_snapshot=None) -> EngineOutput`.
  dict 입력도 가능하고, 커스텀 공급자는 `load(EngineInput) -> MarketDataset`를 구현한다.
- 콜백은 평가 거래일당 1번, 새 스냅샷 1개와 완료/전체 거래일 수를 전달한다. 준비 데이터는 제외한다.
  콜백 객체는 복사본이므로 Consumer의 변경이 최종 결과를 오염시키지 않는다.
- 금액·비율·수량은 JSON decimal 문자열, 주문/체결/거래 건수는 정수다.
  계산 불가능한 지표와 OPEN 거래의 미실현 필드는 null을 유지한다.
- `portfolioSeries`는 매일 존재한다. `positions`는 첫날·마지막 날·수량 변경일에만 존재하며,
  생략은 구성 스냅샷 없음, 빈 배열은 현금 상태를 뜻한다.
- 데이터/엔진 오류는 `run.status=FAILED`, `run.error={code, message}`, null 성과를 반환한다.
  잘못된 EngineInput은 ValidationError, 콜백 실패는 CallbackError를 발생시킨다.
  Consumer는 결과가 COMPLETED인지 확인해야 하며, 콜백 100%가 저장 성공을 뜻하지 않는다.
- 주문 원장과 재현성 metadata는 메시지 계약에 없다. 필요하면 `run_engine` 대신
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

### 검증 결과와 제약 (2026-09-07)

- 고정 데이터의 실제 Nautilus 실행: 다음 날 체결, 미래 시가와 무관한 수량, 중복 주문 방지,
  잔여 현금, 전량 거절 후 재평가, 양방향 슬리피지, OPEN/CLOSED, 지표, 콜백을 pytest로 검증.
- 실제 SK하이닉스 **000660.KS / SK hynix Inc. / KRW / KSC** 확인 후 2020-09-18~2022-12-30
  요청 성공. 실제 평가 종료는 2022-12-29, 평가 **561일**, 준비 **249일**.
  최종 자산 **11,464,055.150381 KRW**, CLOSED **7건**, 체결 **14건**, 현금 부족 거절 **12건**.
- 최근 약 5년 요청(2021-09-06~2026-09-06)은 **실패**했다. Yahoo가 2025-09-19에 모든 OHLC를
  NaN으로 반환했다. 다른 5년 구간에서는 2023-02-02, 2023-02-09, 2024-10-14의 OHLC 범위
  오류도 확인했다. 이를 임의 수정·삭제하지 않고 날짜를 포함한 오류를 반환한다.
  기본 5년 실행 성공을 보장하지 않으며, 공급자 데이터 정정 또는 다른 명시적 기간이 필요하다.
- 조정 방식은 yfinance `auto_adjust=True`로 배당과 분할을 모두 반영한다. 실제 KRX 틱 규칙,
  기업행동/거래정지/상장폐지 원인 분류는 구현하지 않는다. 실제 원화의 정수 단위 반올림을 피하기
  위해 native 계정에는 `KRW_SIM`(KRW와 1:1, 8자리)을 사용하고 출력은 KRW로 표시한다.
  가격은 6자리로 정규화한다. 실거래용 브로커 모델이 아니다.
- yfinance는 시세 정정 가능성이 있는 임시 공급자다. dataHash/버전/실제 기간을 기록하지만
  장기 재현을 위해서는 향후 원시 데이터 스냅샷 보관 공급자로 교체해야 한다.

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
