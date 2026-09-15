# 계약: 등록표 내부 포트 (003 → 007)

**Spec**: [spec.md](../spec.md) | **Research**: [research.md](../research.md) R2 | **Date**: 2026-09-15

007이 003의 등록표를 읽는 통로다. [007의 param-search-internal.md](../../007-auto-parameter-search/contracts/param-search-internal.md) §1이 007 쪽에서 본 같은 계약을 적고 있으며, 이 문서는 **003이 그 계약에 대해 약속하는 것**을 적는다.

## 1. 현재 상태와 이 spec이 바꾸는 것

007이 자기 작업을 진행하려고 포트와 임시 구현을 먼저 세워 두었다.

| 것 | 지금 위치 | 003 구현 후 |
|---|---|---|
| `ParameterRegistryPort` | `paramsearch/application/port/` | `strategy/application/port/`로 옮긴다 |
| `StrategyRegistration` `ParameterAxis` `AllowedRange` | `paramsearch/application/port/` | 같이 옮긴다 |
| `ProvisionalFileParameterRegistry` | `paramsearch/infrastructure/registry/` | `strategy/infrastructure/registry/`의 정식 구현으로 교체한다 |
| `parameter-registry-provisional.yml` | `resources/registry/` | `parameter-registry.yml`로 승격한다 |

**007 운영 코드는 바뀌지 않는다.** `ParamSearchService`·`CandidateValidator`·`SearchTargetResolver`가 포트 타입만 참조하고 구현을 직접 부르지 않으므로, import 경로 외에 손댈 곳이 없다. 이 사실은 옮기기 전에 테스트로 확인한다.

**교체 시점**: 스펙이 정한 대로 임시 구현 클래스의 주석("003이 완성되면 이 클래스를 003 구현체로 교체한다")을 따른다.

## 2. 003이 약속하는 것

### 2.1 버전은 한 번 읽으면 흔들리지 않는다

`version()`이 돌려주는 값이 같은 동안, `activeStrategies()`가 돌려주는 전략 순서와 각 전략의 축 순서, 축의 `searchValues` 순서를 바꾸지 않는다.

**왜**: 007이 저장한 `grid_coordinates`가 그 순서를 뜻으로 삼는다. 순서가 흔들리면 끝난 탐색의 좌표가 다른 조합을 가리키게 되고 인접 판정이 성립하지 않는다.

순서를 바꿔야 하면 `version()`을 올린다. 007은 버전이 다른 탐색 묶음을 섞지 않는다.

### 2.2 조회 실패를 대체하지 않는다

등록표를 읽지 못하면 예외를 던진다. 캐시된 값이나 기본값으로 채워 돌려주지 않는다.

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

### 2.5 `searchValues`는 사용자가 고를 수 있는 값이다

목록형 축에서 `searchValues ⊆ allowedRange.values`를 지킨다. 로더가 기동 시 검사하고 위반하면 기동을 실패시킨다.

**왜**: 자동 탐색이 사용자가 고를 수 없는 값을 훑으면, 사용자가 그 결과를 상세 설정에서 재현할 수 없다.

**두 목록을 합치지 않는다**: `allowedRange.values`는 003 상세 설정의 선택지이고 `searchValues`는 007이 전수 계산할 값이다. 후자를 늘리면 조합 수가 곱으로 늘어나므로 003은 두 목록을 따로 조절한다. 값이 같아도 무방하며 제약은 포함 관계만 본다.

## 3. 003이 더하는 것

기존 포트에 없던 것을 더한다. 007은 부르지 않아도 된다.

| 메서드 | 돌려주는 것 | 누가 쓰나 |
|---|---|---|
| `analysisConditions()` | 시작 금액의 허용 범위와 기본값 | 003 화면 |
| `StrategyRegistration.active()` | 활성 여부 | 003 화면, 007의 `activeStrategies` 구현 |
| `StrategyRegistration.warmup()` | 워밍업 산출 규칙(`sum` 배열) | 003 화면, 004 |

**`warmup()`이 007에도 뜻이 있는가**: 007이 탐색 후보마다 다른 워밍업을 갖게 되므로 평가 시작일을 맞추려면 이 값이 필요하다. 다만 007이 그것을 어떻게 쓸지는 007이 정할 일이고, 003은 값을 제공하는 데까지만 약속한다.

## 4. 옮기는 순서

의존이 한 방향으로만 흐르도록 순서를 지킨다.

1. `strategy` 패키지를 만들고 포트와 값 타입을 옮긴다. `paramsearch`는 새 위치를 import한다.
2. 007 테스트를 돌려 통과를 확인한다. 이 단계에서 동작이 바뀌면 안 된다.
3. YAML 로더를 `strategy` 쪽으로 옮기고 파일을 `parameter-registry.yml`로 승격한다. 새 필드(`active`·`warmup`·`analysisConditions`)를 더한다.
4. 007 테스트를 다시 돌린다. 새 필드가 007 동작을 바꾸지 않아야 한다.
5. 조회 API와 화면을 만든다.

**2단계와 4단계를 건너뛰지 않는다.** 등록표는 지금 007이 실제로 쓰고 있어 조용히 깨지면 자동 탐색이 먼저 멈춘다.
