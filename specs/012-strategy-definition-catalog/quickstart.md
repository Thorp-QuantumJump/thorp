# Quickstart: 전략 정의 카탈로그 검증 가이드

**Feature**: `specs/012-strategy-definition-catalog` | **Date**: 2026-09-03

구현이 끝났을 때 "정말 되는가"를 확인하는 절차다. 구현 코드는 여기 담지 않는다.

## 사전 조건

- Docker / Docker Compose (Postgres·RabbitMQ 기동용 — 카탈로그 자체는 DB를 쓰지 않지만 앱 기동에 필요)
- JDK 21, Node 20+, `uv` (Worker는 Python 3.14 — `uv`가 인터프리터를 직접 받아온다)
- 저장소 루트에서 `docker compose -f docker-compose.dev.yml up -d postgres rabbitmq`

---

## 1. 계약 문서 검증 (가장 먼저)

```bash
cd docs/specs/strategy-definition
uv run --python 3.11 --with jsonschema python scripts/validate_definitions.py
```

**기대 결과**: `backend/src/main/resources/strategies/*.json` 전부가 `OK`. 하나라도 `FAIL`이면 그 정의는
기동 시 카탈로그에서 제외되므로 여기서 먼저 잡는다.

---

## 2. Backend — 카탈로그 로딩과 판정

```bash
cd backend
./gradlew spotlessApply build
```

**기대 결과**

- `ArchitectureTest` 통과 — `domain/policy`가 Spring·Jackson을 참조하지 않음이 확인된다.
- `ParameterSetValidatorVectorTest`가 `docs/specs/strategy-definition/test-vectors/parameter-validation.json`
  의 전 케이스를 통과. 이 테스트가 SC-002·SC-010의 BE 쪽 증거다.
- `StrategyDefinitionFormatValidatorTest`가 D1~D13 각각에 대해 위반 정의를 거부함을 확인.

앱을 띄워 실제 응답을 본다:

```bash
./gradlew bootRun
```

```bash
# 전략 목록 — 4종이 나와야 한다
curl -s localhost:8080/strategies | jq '.data[].strategyId'
# → "MACD_SIGNAL" "MOMENTUM" "RSI_REVERSION" "SMA_CROSSOVER"

# 전략 정의 상세
curl -s localhost:8080/strategies/SMA_CROSSOVER | jq '.data.parameters[].name'
# → "shortPeriod" "longPeriod" "allowShort" "rebalancePeriod"

# 유효한 값 — 기본값이 채워지고 이력 요구량이 산출된다
curl -s -X POST localhost:8080/strategies/SMA_CROSSOVER/parameter-validation \
  -H 'Content-Type: application/json' \
  -d '{"parameters":{"shortPeriod":20,"longPeriod":200}}' | jq '.data'
# → valid: true, resolvedParameters 4개 전부, requiredHistoryBars: 201

# 여러 위반 — 한 번에 전부 보고되는지 (FR-012, SC-004)
curl -s -X POST localhost:8080/strategies/SMA_CROSSOVER/parameter-validation \
  -H 'Content-Type: application/json' \
  -d '{"parameters":{"shortPeriod":"x","longPeriod":500,"rebalancePeriod":"HOURLY","unknownKnob":1}}' \
  | jq '.data.violations[] | {code, parameterName}'
# → UNKNOWN_PARAMETER/unknownKnob, TYPE_MISMATCH/shortPeriod,
#    OUT_OF_RANGE/longPeriod, NOT_IN_ENUM/rebalancePeriod  (이 순서 그대로)

# 상호 제약 (FR-009)
curl -s -X POST localhost:8080/strategies/SMA_CROSSOVER/parameter-validation \
  -H 'Content-Type: application/json' \
  -d '{"parameters":{"shortPeriod":100,"longPeriod":100}}' | jq '.data.violations'
# → CONSTRAINT_VIOLATED / shortPeriod

# 없는 전략
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/strategies/NOPE
# → 404
```

**형식 위반 정의가 나머지를 막지 않는지 확인 (FR-017)**

```bash
# 일부러 깨진 정의를 넣고 기동한다
echo '{"schemaVersion":"1.0.0","strategyId":"BROKEN"}' \
  > backend/src/main/resources/strategies/BROKEN.json
./gradlew bootRun
```

기대: 기동은 성공하고 `WARN` 로그에 `BROKEN`과 위반 항목이 남으며, `GET /strategies`는 여전히 4종을
반환한다. 확인 후 파일을 지운다.

**정의가 하나도 없을 때 (spec Edge Cases)**: `strategies/` 디렉터리를 비우고 기동하면 앱은 정상 기동하고
`GET /strategies`가 `{"success":true,"data":[],"error":null}`를 반환해야 한다. 오류가 아니다.

---

## 3. Frontend — 동적 폼과 판정 미러

```bash
cd frontend
npm ci
npm run test    # vitest — BE와 같은 테스트 벡터를 읽는다
npm run lint && npm run build
npm run dev
```

**기대 결과**

- `strategyValidation.test.ts`가 공유 벡터 전 케이스를 통과. BE와 **같은 파일**을 읽으므로, 두 스위트가
  모두 초록이면 SC-002가 증거를 갖는다.
- 화면에서 전략을 고르면 파라미터 입력 폼이 정의만으로 구성된다:
  `shortPeriod`/`longPeriod`는 `min`·`max`·`step`이 붙은 숫자 입력, `allowShort`는 스위치,
  `rebalancePeriod`는 `displayName`이 보이는 셀렉트.
- 전략을 바꾸면 폼이 새 정의의 파라미터로 교체되고 이전 전략에만 있던 입력은 사라진다(spec US1 시나리오 2).
- 범위를 벗어난 값을 넣으면 서버 왕복 없이 즉시 메시지가 뜬다. 제출 시 서버 판정이 한 번 더 수행되며
  두 결과가 달라서는 안 된다.

---

## 4. Worker — Nautilus 전략 구성과 정의 일치

```bash
cd worker
uv run --python 3.14 pytest
uv run --python 3.14 ruff check src tests && uv run --python 3.14 ruff format --check src tests
```

**기대 결과**

`test_strategy_definition_contract.py`가 통과한다. 이 테스트는

- 정의 4종 각각의 `strategyId`가 `registry.STRATEGY_PATHS`에 있고,
- 등록된 항목 각각에 정의가 있고,
- 정의의 파라미터 이름을 snake_case로 바꾼 집합이 `config_path`가 가리키는 `StrategyConfig` 서브클래스의
  자체 선언 필드 집합(예약 필드 16개 제외)과 정확히 같고,
- 각 파라미터의 `type`이 설정 클래스의 필드 애노테이션과 대응(`INTEGER`→`int`, `DECIMAL`→`Decimal`,
  `BOOLEAN`→`bool`, `ENUM`→`str`/`Literal`)함

을 확인한다. `../backend/src/main/resources/strategies/`가 없으면 skip된다(Worker 이미지 내부).

`test_binding.py`가 변환 규칙을 확인한다.

- `resolvedParameters`의 camelCase 키가 snake_case로 바뀐다
- `DECIMAL` 값이 문자열로 실려 `Decimal`로 정확히 복원된다 — `0.1 + 0.2`를 넣어 `Decimal('0.3')`이
  나오는지 본다. float으로 실으면 `Decimal('0.30000000000000004')`가 되므로 이 케이스가 회귀 방지선이다
- `StrategyFactory.create()`가 실제 `Strategy` 인스턴스를 만든다

**의도적으로 깨뜨려 보기 (둘 다 실패해야 한다)**

1. `SMA_CROSSOVER.json`에 파라미터를 하나 추가하고 `SmaCrossoverConfig`는 그대로 둔다 → 계약 테스트 실패
2. `SmaCrossoverConfig`에 `order_id_tag: str` 필드를 넣는다 → 예약 필드 충돌(D14)로 형식 검증 실패

실패하지 않으면 계약 보증이 작동하지 않는 것이다.

---

## 5. 새 전략 추가가 정말 3개 파일로 끝나는지 (SC-001)

가장 중요한 검증이다. 다음 세 파일만 건드려 새 전략을 추가한다.

1. `backend/src/main/resources/strategies/<STRATEGY_ID>.json` — 새 정의
2. `worker/src/worker/strategies/<name>.py` — `XxxConfig(StrategyConfig)` + `Xxx(Strategy)`
3. `worker/src/worker/strategies/registry.py` — `STRATEGY_PATHS`에 한 줄 등록

그리고 **다음을 하나도 고치지 않은 채로** 위 2·3·4절을 다시 실행한다.

- Backend: 컨트롤러·서비스·검증기·도메인 모델
- Worker: `binding.py`·`naming.py`·`models.py`
- Frontend: 타입·api 레이어·`ParameterForm`·판정 미러
- 계약 스키마

전부 통과하면 SC-001이 충족된 것이다. 어느 하나라도 고쳐야 했다면 그 지점이 확장 비용이며,
정의 형식의 어휘를 넓히는 방향으로 되돌려야 한다(전략별 예외를 추가하지 않는다).

---

## 6. 전체 CI 흐름

```bash
# Jenkins가 도는 것과 같은 순서
cd frontend && npm ci && npm run lint && npm run test && npm run build
cd ../backend && ./gradlew clean test build
cd ../worker && uv run --python 3.14 ruff check src tests && uv run --python 3.14 pytest
```

네 스위트가 모두 통과하면 이 기능은 완료 조건을 만족한다.
