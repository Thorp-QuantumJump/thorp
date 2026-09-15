# 계약: 등록표 내부 포트 (003 → 007)

**Spec**: [spec.md](../spec.md) | **Research**: [research.md](../research.md) R2 | **Date**: 2026-09-15

007이 003의 등록표를 읽는 통로다. [007의 param-search-internal.md](../../007-auto-parameter-search/contracts/param-search-internal.md) §1이 007 쪽에서 본 같은 계약을 적고 있으며, 이 문서는 **003이 그 계약에 대해 약속하는 것**을 적는다.

## 1. 현재 상태와 이 spec이 바꾸는 것

007이 자기 작업을 진행하려고 포트와 임시 구현을 먼저 세워 두었다.

| 것 | 지금 위치 | 003 구현 후 |
|---|---|---|
| `ParameterRegistryPort` | `paramsearch/application/port/` | `strategy/application/port/`로 옮긴다 |
| `StrategyRegistration` `ParameterAxis` `AllowedRange` | `paramsearch/application/port/` | 같이 옮긴다 |
| `RegistryUnavailableException` | `paramsearch/application/exception/` | `strategy/application/exception/`으로 옮긴다 |
| `ProvisionalFileParameterRegistry` | `paramsearch/infrastructure/registry/` | `strategy/infrastructure/registry/`로 옮기고 이름을 바꾼다 (아래) |
| `parameter-registry-provisional.yml` | `resources/registry/` | `parameter-registry.yml`로 승격한다 |

**로더에 하는 일은 셋이다.** '교체'나 '이동' 한 단어로 뭉치면 읽는 사람이 자기 해석대로 움직인다.

1. **옮긴다** — 기존 파싱과 검사 로직을 그대로 살린다. 지금 로더는 축 이름 중복·빈 `searchValues`·`null` 값을 기동 시에 막고 있다. 새로 짜면 이 중 하나를 빠뜨려도 알아채지 못한다.
2. **이름을 바꾼다** — 임시 대역이 아니라 정식 구현이 되므로 `Provisional`이 맞지 않는다.
3. **읽는 항목을 늘린다** — `active`·`warmup`·`analysisConditions`를 파싱하고, 새 제약 셋(§2.5, `defaultValue`가 허용 범위 안, 목록형·입력형 배타)을 검사에 더한다.

**예외를 함께 옮기는 이유**: 이 예외의 뜻은 "등록표를 읽지 못했다"이고 등록표의 주인이 003이 된다. 003 조회 API도 같은 상황에서 503 `REGISTRY_UNAVAILABLE`을 내야 하는데, 예외가 007 패키지에 남으면 003이 007을 참조하게 되어 없애려던 역방향 의존이 되살아난다. 007은 옮긴 예외를 가져다 쓰며 지금처럼 자기 호출부를 감싸는 방식은 그대로 둔다.

**007 운영 코드에서 바뀌는 것은 import 경로뿐이다.** `ParamSearchService`·`CandidateValidator`·`SearchTargetResolver`가 포트 타입만 주입받고 구현을 직접 부르지 않으므로 그 밖에 손댈 곳이 없다. 이 사실은 옮기기 전에 테스트로 확인한다.

**교체 시점**: 스펙이 정한 대로 임시 구현 클래스의 주석("003이 완성되면 이 클래스를 003 구현체로 교체한다")을 따른다.

## 2. 003이 약속하는 것

### 2.1 버전은 한 번 읽으면 흔들리지 않는다

`version()`이 돌려주는 값이 같은 동안, `activeStrategies()`가 돌려주는 전략 순서와 각 전략의 축 순서, 축의 `searchValues` 순서를 바꾸지 않는다.

**왜**: 007이 저장한 `grid_coordinates`가 그 순서를 뜻으로 삼는다. 순서가 흔들리면 끝난 탐색의 좌표가 다른 조합을 가리키게 되고 인접 판정이 성립하지 않는다.

순서를 바꿔야 하면 `version()`을 올린다. 007은 버전이 다른 탐색 묶음을 섞지 않는다.

**`allowedRange.values`의 순서는 얼리지 않는다.** 이것은 화면 드롭다운에 보이는 순서일 뿐이고 어디에도 저장되지 않는다. 읽기 좋게 바꿔도 되며 **버전을 올릴 필요가 없다.**

| | 순서가 뜻하는 것 | 바꾸려면 |
|---|---|---|
| `searchValues` | 007이 저장한 좌표. `[21, 63, 126, 252]`에서 "1번"은 63이다 | 버전을 올린다 |
| `allowedRange.values` | 화면 표시 순서. 저장되는 것이 없다 | 그냥 바꾼다 |

이 자유는 두 목록을 나눠 둔 덕분에 생긴다(§2.5). 합쳐 두면 드롭다운 순서를 바꾸는 일에도 버전을 올려야 하고, 그러면 열려 있던 화면의 설정이 버려지고 접수가 409로 거절된다.

### 2.2 조회 실패를 대체하지 않는다

등록표를 읽지 못하면 `RegistryUnavailableException`(`ErrorCode.REGISTRY_UNAVAILABLE`, 503)을 던진다. 캐시된 값이나 기본값으로 채워 돌려주지 않는다. **003과 007이 같은 예외를 쓴다** — 같은 실패에 두 화면이 다른 상태 코드를 내면 원인을 찾기 어렵다.

**왜**: 007이 그 값으로 탐색을 실행하면 묶음의 참조 버전이 뜻을 잃는다. 003 화면에서도 같은 이유로 조회 실패 시 분석을 시작하지 않는다(FR-018).

### 2.3 활성과 등록을 구분한다

| 메서드 | 비활성 전략을 포함하나 |
|---|---|
| `activeStrategies()` | 아니오 |
| `find(strategyId)` | 예 |

**왜**: 새 실행은 활성 전략만 받지만, 과거 기록을 해석할 때는 그 시점에 쓰인 전략의 이름과 축 구성이 필요하다. 비활성 전략을 등록표에서 지우면 그 기록을 읽을 수 없다 (research R5).

### 2.4 값 비교는 `equals`만 쓴다

`ParameterAxis.indexOf`가 `equals`로 판정하므로, 003은 같은 뜻의 값을 두 형태로 담지 않는다. 예를 들어 `63`과 `63.0`을 같은 축에 섞지 않는다.

리밸런싱 주기처럼 값이 객체인 축은 값 비교가 성립하는 형태로 담는다 (research R7).

### 2.5 `searchValues`는 사용자 선택지의 부분집합이다

목록형 축에서 `searchValues ⊆ allowedRange.values`를 지킨다. 로더가 기동 시 검사하고 위반하면 기동을 실패시킨다.

**왜**: 자동 탐색이 사용자가 고를 수 없는 값을 훑으면, 사용자가 그 결과를 상세 설정에서 재현할 수 없다.

**두 목록을 합치지 않는다**: `allowedRange.values`는 003 상세 설정의 선택지이고 `searchValues`는 007이 전수 계산할 값이다. 후자를 늘리면 조합 수가 곱으로 늘어나므로 003은 두 목록을 따로 조절한다. 값이 같아도 무방하며 제약은 포함 관계만 본다.

## 3. 003이 더하는 것

기존 포트에 없던 것을 더한다. 007은 부르지 않아도 된다.

| 메서드 | 돌려주는 것 | 누가 쓰나 |
|---|---|---|
| `rules(strategyId)` | 그 전략의 유효성 규칙 **목록** | 003 조회 API |
| `analysisConditions()` | 시작 금액과 목표 변동성의 허용 범위·기본값 | 003 화면 |
| `StrategyRegistration.active()` | 활성 여부 | 003 화면, 007의 `activeStrategies` 구현 |
| `StrategyRegistration.warmup()` | 워밍업 산출 규칙(`sum` 배열) | 003 화면, 004 |

**`rules()`가 왜 필요한가**: 기존 `validate()`는 조합 하나를 받아 **판정 결과만** 돌려준다. 007에는 그것으로 충분하다 — 조합을 만들어 하나씩 물어보면 되고 조건이 무엇인지 알 필요가 없다.

그러나 003 화면은 **조건 자체**가 필요하다. 사용자가 비중 상한 칸에 숫자를 치는 동안 그 자리에서 판정해야 하는데(FR-008, [research](../research.md) R7-1), 서버에 물으면 타이핑마다 왕복이 생기고 늦게 온 답이 이미 바뀐 값을 판정한다. 규칙은 지금 로더 안에 갇혀 있고 밖으로 나오는 문이 없으므로 이 메서드를 더한다.

`validate()`는 그대로 둔다. 007이 계속 쓰고, 003 서버도 접수 시점 판정에 쓴다.

**`warmup()`이 007에도 뜻이 있는가**: 007이 탐색 후보마다 다른 워밍업을 갖게 되므로 평가 시작일을 맞추려면 이 값이 필요하다. 다만 007이 그것을 어떻게 쓸지는 007이 정할 일이고, 003은 값을 제공하는 데까지만 약속한다.

## 4. 옮기는 순서

의존이 한 방향으로만 흐르도록 순서를 지킨다.

1. `strategy` 패키지를 만들고 포트·값 타입·예외를 옮긴다. `paramsearch`는 새 위치를 import한다.
2. 007 테스트를 돌려 통과를 확인한다. 이 단계에서 동작이 바뀌면 안 된다.
3. YAML 로더를 `strategy` 쪽으로 옮기고(§1의 셋) 파일을 `parameter-registry.yml`로 승격한다. 새 필드(`active`·`warmup`·`analysisConditions`)를 더하고, 목록형 축의 `allowedRange`를 `values`로 채운다.
4. 007 테스트를 다시 돌린다. 새 필드가 007 동작을 바꾸지 않아야 한다.
5. 조회 API와 화면을 만든다.

**2단계와 4단계를 건너뛰지 않는다.** 등록표는 지금 007이 실제로 쓰고 있어 조용히 깨지면 자동 탐색이 먼저 멈춘다.
