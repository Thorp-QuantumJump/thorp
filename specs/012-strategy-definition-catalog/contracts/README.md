# Contracts: 전략 정의 카탈로그

이 디렉터리가 전략 정의 계약의 **설계 시점 정본**이다. 구현 첫 작업으로 `docs/specs/strategy-definition/`
으로 옮기고 그 이후에는 그곳을 유일 출처로 삼는다(헌장 IV: 파트 경계 계약은 `docs/specs/`에 문서화).

| 파일 | 역할 | 소비자 |
|---|---|---|
| `strategy-definition.schema.json` | 전략 정의 하나의 구조 | BE 로더·형식 검증, FE 타입, 정의 작성자 |
| `strategy-catalog-api.schema.json` | 카탈로그 조회·값 판정 API의 요청·응답 | BE 컨트롤러, FE api 레이어 |
| `strategy-execution-binding.schema.json` | 실행 요청 메시지에 실리는 전략 바인딩 | BE 발행, Worker 소비 |
| `examples/*.json` | 초기 4종 전략 정의. 그대로 `backend/src/main/resources/strategies/`로 들어간다 | BE 리소스 |
| `test-vectors/parameter-validation.json` | 판정 결과 동일성 검증용 공유 벡터 (20 케이스, 전부 `BACKTEST`) | BE JUnit, FE vitest |
| `test-vectors/execution-scope.json` | 실행 목적 필터링 전용 벡터 (3 케이스, 같은 형식) | BE JUnit, FE vitest |

벡터를 두 파일로 나눈 이유: 값 판정(US2)과 실행 목적 필터링(US4)은 서로 다른 사용자 스토리다. 한 파일에
섞여 있으면 US2의 테스트가 US4 구현 없이는 통과할 수 없어 스토리를 독립적으로 완료·검증할 수 없다.
형식이 같으므로 테스트 하네스는 하나만 만들면 된다.

## 실행 엔진과의 관계

전략 실행 엔진은 **NautilusTrader로 확정**됐지만(research.md R8), 이 디렉터리의 스키마 어디에도 Nautilus
개념이 없다. 그렇게 유지한다.

- `strategy-execution-binding.schema.json`은 `strategyId` · `definitionVersion` ·
  `resolvedParameters` · `requiredHistoryBars` · `executionScope`만 담는다.
- Nautilus `ImportableStrategyConfig`로의 변환은 Worker 내부
  `worker/src/worker/strategies/binding.py` 한 곳에서만 일어난다.
- Backend와 Frontend는 엔진을 모른다. 엔진을 바꿔도 이 계약과 두 파트는 바뀌지 않는다.

**한 곳만 예외다**: 형식 검증 규칙 D14(파라미터 이름이 `StrategyConfig` 예약 필드와 겹치면 거부)는
엔진에서 유래한 제약이다. 겹치면 사용자 값이 엔진 동작 설정을 덮어쓰기 때문에 정의 단계에서 막아야 하고,
스키마가 아니라 Backend 검증기의 상수 목록으로 둔다. 계약 문서는 여전히 엔진 중립이다.

## 스키마가 담지 않는 것

`strategy-definition.schema.json`은 **한 파일 안에서 표현 가능한 구조 규칙**만 담는다. 다음은 담지 않으며
`StrategyDefinitionFormatValidator`(BE)가 검사한다 — 목록과 근거는 `../data-model.md` §2의 D1~D13 표.

- 카탈로그 전체에 걸친 `strategyId` 유일성 (파일 하나로는 알 수 없음)
- `defaultValue`가 자기 `numericRange`/`enumValues`를 만족하는지
- `parameterConstraints`와 `minimumHistoryRequirement`가 참조하는 파라미터의 존재·타입
- `changeHistory`의 정렬과 최상위 `definitionVersion`과의 일치

스키마 하나에 모든 규칙을 밀어 넣지 않는 것이 이 설계의 의도다. 위 규칙들을 스키마로 표현하면 위반
메시지가 "어떤 파라미터가 왜 잘못됐는지"를 잃는다.

## 검증

정의 파일이 스키마를 만족하는지 확인:

```bash
uv run --python 3.11 --with jsonschema python - <<'PY'
import glob, json
from jsonschema import Draft202012Validator
v = Draft202012Validator(json.load(open("strategy-definition.schema.json", encoding="utf-8")))
for p in sorted(glob.glob("examples/*.json")):
    errs = list(v.iter_errors(json.load(open(p, encoding="utf-8"))))
    print(("FAIL " if errs else "OK   ") + p)
    for e in errs[:5]:
        print("   ", list(e.path), e.message)
PY
```

이 명령은 설계 시점에 실행해 `examples/` 4종과 테스트 벡터의 합성 정의 3종이 모두 통과함을 확인했고,
스키마가 잡아야 할 형태 10가지(`required`+`defaultValue`, `ENUM`+`numericRange`, `min` 없는 `step`,
`FIXED`+`parameters` 등)를 모두 거부함을 함께 확인했다. 같은 검사를 CI에 넣는 작업은 tasks 단계에서 다룬다.
