# 전략 정의 계약 (Strategy Definition Contract)

전략 메타데이터의 **단일 출처**다. Backend·Frontend·Worker가 전략을 이해하는 방식은 전부 여기서 나온다.
설계 문서는 `specs/012-strategy-definition-catalog/`에 있지만, 계약의 정본은 이 디렉터리다(헌장 IV).

## 무엇이 어디에 있나

| 경로 | 역할 | 소비자 |
|---|---|---|
| `strategy-definition.schema.json` | 전략 정의 하나의 구조 | BE 로더·형식 검증, FE 타입, 정의 작성자 |
| `strategy-catalog-api.schema.json` | 카탈로그 조회·값 판정 API의 요청·응답 | BE 컨트롤러, FE api 레이어 |
| `strategy-execution-binding.schema.json` | 실행 요청 메시지에 실리는 전략 바인딩 | BE 발행, Worker 소비 |
| `examples/*.json` | API 요청·응답 예시. 배포 정의와 일치함을 검증기가 대조한다 | 계약을 읽는 사람, 소비자 구현 |
| `test-vectors/parameter-validation.json` | 판정 결과 동일성 검증용 공유 벡터 (20 케이스, 전부 `BACKTEST`) | BE JUnit, FE vitest |
| `test-vectors/execution-scope.json` | 실행 목적 필터링 전용 벡터 (3 케이스, 같은 형식) | BE JUnit, FE vitest |
| `scripts/validate_definitions.py` | 위 전부를 대조하는 검증기 | 개발자, CI |

**전략 정의 파일 자체는 `backend/src/main/resources/strategies/`에 있다.** Backend가 유일한 런타임
소비자이므로 그 클래스패스가 정의의 거처다. 이 디렉터리에는 정의 파일의 사본을 두지 않는다 — 사본이
생기는 순간 어느 쪽이 맞는지 답할 수 없게 된다.

테스트 벡터의 `definitionSources[].ref`는 **배포 정의 루트(`backend/src/main/resources/`) 기준 상대
경로**다(예: `strategies/SMA_CROSSOVER.json`). BE는 클래스패스에서, FE는 파일 경로로 같은 파일을 읽는다.
합성 정의가 필요한 케이스는 `ref` 대신 `inline`으로 정의 전체를 담는다.

## 새 전략을 추가할 때

파일 3개면 끝난다. 소비 지점(컨트롤러·서비스·판정기·FE 폼·미러)은 건드리지 않는다.

1. `backend/src/main/resources/strategies/{STRATEGY_ID}.json` — 정의. 파일명과 `strategyId`가 같아야 한다
2. `worker/src/worker/strategies/{strategy_id}.py` — 전략 구현과 설정 클래스
3. `worker/src/worker/strategies/registry.py` — 등록 한 줄

고쳐야 할 소비 지점이 생겼다면 그 전략만을 위한 예외를 넣지 말고 **정의 형식의 어휘를 넓히는 쪽으로**
되돌린다. 어휘를 넓히면 스키마·데이터 모델·판정기·FE 미러를 함께 고친다.

## 검증

```bash
uv run --python 3.14 --with jsonschema python \
    docs/specs/strategy-definition/scripts/validate_definitions.py
```

검사 항목: 배포 정의가 스키마를 만족하는지, 파일명과 `strategyId`가 같은지, 테스트 벡터의 `ref`가
실제 정의를 가리키는지, 예시가 스키마를 만족하는지, 그리고 **예시가 배포 정의와 어긋나지 않는지**.
마지막 항목이 이 검증기의 존재 이유다 — 스키마만 검사하면 문서가 조용히 낡는다.

## 스키마가 담는 것과 담지 않는 것

`strategy-definition.schema.json`은 **파일 하나 안에서 표현 가능한 구조 규칙**만 담는다.

담는 것: 필드의 존재·타입·패턴, 타입별 범위 표현의 배타성(`INTEGER`/`DECIMAL`↔`numericRange`,
`ENUM`↔`enumValues`), `INTEGER`의 경계·간격이 정수인지, `required`와 `defaultValue`의 배타성과
**선택 파라미터의 기본값 필수**, `step`이 `min`을 요구하는 것, `FIXED`와 대상 파라미터의 배타성,
`definitionVersion: 1`과 `INITIAL`의 대응.

담지 않는 것: 여러 항목을 대조해야 알 수 있는 규칙 D1~D18. `StrategyDefinitionFormatValidator`(BE)가
검사하며 목록과 근거는 `specs/012-strategy-definition-catalog/data-model.md` §2에 있다. 위반한 정의만
카탈로그에서 빠지고 나머지는 정상 제공된다(FR-017).

스키마 하나에 모든 규칙을 밀어 넣지 않는 것이 이 설계의 의도다. 위 규칙들을 스키마로 표현하면 위반
메시지가 "어떤 파라미터가 왜 잘못됐는지"를 잃는다.

## 실행 엔진과의 관계

전략 실행 엔진은 NautilusTrader로 확정됐지만 이 디렉터리의 스키마 어디에도 Nautilus 개념이 없다.
그렇게 유지한다.

- `strategy-execution-binding.schema.json`은 `strategyId` · `definitionVersion` ·
  `resolvedParameters` · `requiredHistoryBars` · `executionScope`만 담는다.
- Nautilus `ImportableStrategyConfig`로의 변환은 `worker/src/worker/strategies/binding.py`
  한 곳에서만 일어난다.
- Backend와 Frontend는 엔진을 모른다. 엔진을 바꿔도 이 계약과 두 파트는 바뀌지 않는다.

**한 곳만 예외다**: 형식 검증 규칙 D14(파라미터 이름이 `StrategyConfig` 예약 필드와 겹치면 거부)는
엔진에서 유래한 제약이다. 겹치면 사용자 값이 엔진 동작 설정을 덮어쓰기 때문에 정의 단계에서 막아야
하고, 스키마가 아니라 Backend 검증기의 상수 목록으로 둔다. 계약 문서는 여전히 엔진 중립이다.
