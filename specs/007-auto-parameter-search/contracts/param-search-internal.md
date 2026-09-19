# 내부 계약: 포트와 인메모리 이벤트

**Date**: 2026-09-10 | **Spec**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md)

007이 다른 모듈·미완성 spec과 만나는 지점을 계약으로 고정한다. RabbitMQ 메시지 계약
(`docs/specs/backtest-messaging/README.md`)은 **변경하지 않는다** — 007은 워커를 바꾸지 않고 기존
작업 메시지를 그대로 쓴다.

---

## 1. `ParameterRegistryPort` — 003과의 경계 (research R1, 2026-09-11 개정)

003이 소유한 전략·파라미터 등록표를 007이 읽는 **유일한** 통로다.
007은 이 포트가 돌려주는 값 외에 전략에 대해 아무것도 알지 못한다(FR-025).

**위치는 003 소유다.** 포트와 값 타입은 `backend/src/main/java/com/ssafy/thorp/strategy/application/port/`에,
`RegistryUnavailableException`은 `strategy/application/exception/`에 있다. 007은 이 경로를 import할
뿐이고 반대 방향 의존은 없다 — 방향은 `paramsearch → strategy` 하나이며 `ArchitectureTest` 규칙 8이
지킨다([003 내부 계약](../../003-strategy-settings/contracts/strategy-registry-internal.md) §1).

```java
public interface ParameterRegistryPort {

    /** 등록표 버전. 팀 미확정 값은 "PROVISIONAL-" 로 시작한다. */
    String version();

    /** 등록표의 값이 아직 팀 확정값이 아닌가. version()의 접두로 판정한다. */
    boolean provisional();

    /** 활성 전략만. 007은 전략 목록을 자체적으로 갖지 않는다(FR-025·FR-026). */
    List<StrategyRegistration> activeStrategies();

    /** 전략 하나의 등록 정보. 비활성 전략도 포함한다. 등록되지 않았으면 empty(FR-026). */
    Optional<StrategyRegistration> find(String strategyId);

    /** 그 전략의 유효성 규칙 목록. 003 조회 API가 쓴다 — 007은 부르지 않아도 된다. */
    List<ValidationRule> rules(String strategyId);

    /** 전략 공통 분석 조건(시작 금액·목표 변동성·최소 평가 기간). 003 화면이 쓴다. */
    AnalysisConditionRegistry analysisConditions();

    /**
     * 조합 하나가 유효성 제약을 만족하는지. 위반 시 사유 코드를 담아 반환한다(FR-006).
     *
     * 조합을 축 이름 → 값의 Map으로 받는다 — 007의 ParameterCombination을 받으면 등록표를 소유한
     * 003이 007 도메인 VO에 의존해 없애려던 역방향 의존이 되살아난다. 호출자가 자기 표현을 풀어 넘긴다.
     */
    ValidationVerdict validate(String strategyId, Map<String, Object> parameters, SearchContext context);

    /** 손실 제약 기준으로 쓸 전략. 등록표가 '기준 전략' 역할로 지정한다(FR-013d). 지정이 없으면 empty. */
    Optional<String> baselineStrategyId();
}

public record StrategyRegistration(
        String strategyId,          // "SECTOR_MOMENTUM"
        String name,                // "흐름을 따라 배분하기" — 표시용. 007이 만들지 않는다
        String description,         // 표시용, null 허용
        List<ParameterAxis> axes,   // 순서 보존. 축이 없으면 빈 목록(조합 1건)
        boolean active,             // 비활성 전략은 activeStrategies()에서 빠지고 find()에는 남는다
        WarmupRule warmup,          // 워밍업 산출 규칙. 없으면 워밍업 0이다
        String targetWeightSource   // USER | EQUAL | COMPUTED — 003 화면이 쓴다
) {}

public record ParameterAxis(
        String name,            // "lookbackDays"
        String label,           // "관측 기간"
        String description,     // 항목의 의미 한 문장. 003 화면이 쓴다, null 허용
        String unit,            // "거래일", null 허용
        List<Object> searchValues,   // 자동 탐색 후보값. 인덱스가 그리드 좌표다
        Object defaultValue,         // 기본 시작값. 007이 정하지 않는다(FR-027)
        AllowedRange allowedRange    // 입력 허용 범위. 상세 변경의 경계(FR-027)
) {}

/**
 * 입력 허용 범위. 목록형 축은 후보 목록만, 입력형 축은 min·max·step을 쓰며 둘을 동시에 채우지 않는다.
 * 등록표가 준 형태를 그대로 옮긴다.
 *
 * 후보는 값과 화면 문구를 함께 담는다(003 내부 계약 §2.6). 007은 문구를 쓰지 않으므로
 * values()로 값만 순서대로 읽는다 — 부분집합 검사·그리드 좌표·미리보기 응답은 그대로다.
 */
public record AllowedRange(
        Object min, Object max, Object step, List<RegisteredChoice> choices) {
    public List<Object> values() { ... }   // 값만 순서대로
}

/** 목록형 후보 하나. label 은 003 화면이 쓰고 007은 보지 않는다. */
public record RegisteredChoice(Object value, String label) {}

public record ValidationVerdict(boolean valid, String reasonCode, String reasonDetail) {}

/** 유효성 판정에 필요한 묶음 맥락 (섹터 수 등). */
public record SearchContext(int universeSize) {}
```

**규약**

- `axes()`의 반환 순서와 `searchValues`의 인덱스는 같은 `version()` 안에서 안정적이어야 한다.
  인덱스가 흔들리면 `grid_coordinates`가 뜻을 잃고 재현이 깨진다(FR-020·FR-029).
- **인접 판정은 `searchValues`의 인덱스만 쓴다.** 007은 값을 해석해 순서를 만들지 않으므로 숫자가
  아닌 축(범주형)도 전략별 분기 없이 같은 규칙으로 다뤄진다(FR-029).
- `validate()`는 계산 없이 판정할 수 있는 것만 본다. **어떤 제약이 있는지는 등록표가 정한다** —
  007은 제약의 종류를 알지 못하고 `reasonCode`를 그대로 저장·표시한다(FR-006·FR-025). 제약 목록과
  그 값은 [003](../../003-strategy-settings/spec.md)의 PD-02다.
- `defaultValue`·`allowedRange`는 상세 변경 화면과 범위 검증이 그대로 쓴다. 007에 기본값 상수나
  범위 상수를 두지 않는다(FR-027). 기본값을 "추천"이나 "최적값"으로 표시하지 않는다(원칙 III).
- **후보의 화면 문구는 003 것이다.** 003이 등록표에 후보별 `label`을 담기 시작했으나(003 내부 계약
  §2.6) 007은 `values()`로 값만 읽으므로 **미리보기 응답의 `allowedRange` 모양은 바뀌지 않았다.**
  007이 대응할 것은 없다 — 문구를 응답에 실으면 007이 화면 표현을 소유하는 셈이라 싣지 않는다.
- 007은 등록표에 없는 값을 절대 만들지 않는다(FR-002). `space`에 등록표 밖 값이 오면 400이다.
- 조회가 실패하면(구현체 예외) 접수를 거부한다 — `503 REGISTRY_UNAVAILABLE`. 캐시된 등록 정보나
  내부 기본값으로 대체하지 않는다(FR-028, R9).
- `baselineStrategyId()`가 empty이면 하락 폭 필터를 적용하지 않고 그 사실을 결과에 표시한다
  (`drawdownFilterApplied = false`). **007이 어느 전략이 기준인지 추정하지 않는다** — 기준 전략 ID를
  코드에 두면 그것이 FR-025 위반이고, 사용자 등록 전략이 늘어나면 곧바로 깨진다 (FR-013d·FR-025).

**구현 `FileParameterRegistry`**(`backend/src/main/java/com/ssafy/thorp/strategy/infrastructure/registry/`):
전략·축·후보값·기본값·허용 범위·유효성 규칙·분석 조건을 자원 파일
`backend/src/main/resources/registry/parameter-registry.yml`에서 읽는다. **Java 코드 상수로 두지
않는다** — 그것이 곧 007의 하드코딩이다(FR-025). 잘못 적힌 등록표로는 기동하지 않는다.

PD-02는 2026-09-15에 확정됐으므로([S15P21E205-254](https://ssafy.atlassian.net/browse/S15P21E205-254))
현재 값은 팀 확정값이고 `provisional()`은 `false`다. 미확정 값을 다시 담아야 하면 `version()`에
`PROVISIONAL-` 접두를 붙이며, 그때 코드는 바뀌지 않는다 — API 응답의 `registryVersion`과 화면 표시가
그 사실을 그대로 나른다.

테스트는 같은 포트의 fixture 구현으로 한다. 여섯 번째 가짜 전략을 넣은 fixture로 미리보기·후보 조합
생성이 운영 코드 변경 없이 동작함을 검증한다(SC-010).

## 1.1 `StrategyExecutionSupportPort` — 004와의 경계 (research R8)

"이 전략을 실행할 수 있는가"는 004의 사실이므로 004에 묻는다. 007도 003도 대신 선언하지 않는다.

```java
public interface StrategyExecutionSupportPort {

    /** 004가 현재 실행을 지원하는 전략 ID 전체. */
    Set<String> supportedStrategyIds();
}
```

**규약**

- 탐색 대상 = `activeStrategies()` ∩ `supportedStrategyIds()`. 요청 전략이 앞에 없으면
  `STRATEGY_NOT_REGISTERED`, 뒤에 없으면 `STRATEGY_NOT_EXECUTABLE`로 **조합 생성 전에** 거부하고
  두 사유를 구분해 표시·저장한다(FR-026).
- 004가 워커에 전략을 추가하고 이 목록에 넣으면 007은 코드 변경 없이 그 전략을 탐색 대상으로 얻는다.
- 조회 실패는 등록표 조회 실패와 같이 다룬다(`503 REGISTRY_UNAVAILABLE`).

## 2. `CandidateJobLauncher` — 004와의 경계

후보 1건과 동일 비중 기준을 기존 백테스트 실행 경로로 보낸다. 007은 `EngineInput`을 직접 만들지
않고 `backtest` 모듈의 접수 유스케이스를 호출한다.

후보는 **버전이 고정된 요청**으로 접수한다(`BacktestService.createPinned` + `PinnedVersions.searchBundle()`, 004 T029).
묶음은 접수 때 전략 규칙·등록표 버전을 스냅샷에 고정하므로(FR-012), 그 뒤 004 규칙이 새 버전으로 대체되거나 003 등록표
버전이 올라도 남은 후보는 스냅샷의 버전으로 계산된다. 새 요청으로 접수하면 그 순간부터 후보 발행이 409로 막히고, 그 예외가
결과 반영 트랜잭션을 되돌려 묶음이 RUNNING으로 멈춘다.

```java
public interface CandidateJobLauncher {

    /** 후보 하나를 Job으로 접수하고 backtest_job.id를 돌려준다. */
    long launchCandidate(ParamSearchSnapshot snapshot, SearchCandidate candidate);

    /** 동일 비중 기준 1건을 같은 조건으로 접수한다 (FR-013d). */
    long launchBaseline(ParamSearchSnapshot snapshot);
}
```

**규약**

- 두 메서드는 묶음 스냅샷(`ParamSearchSnapshot`)만 보고 조립한다. 현재 시각의 자료 버전·비용 설정을
  다시 읽지 않는다 — 묶음은 시작 시점 버전으로 끝까지 계산한다(FR-012).
- `launchBaseline()`은 스냅샷의 `baselineStrategyId`와 그 전략의 **등록된 기본 시작값**으로 조립한다.
  어느 전략이 기준인지, 그 전략의 파라미터가 무엇인지 007은 알지 못한다(FR-013d·FR-025).
- 후보와 기준은 같은 섹터 집합·공통 평가 구간·금액·비용·참조 버전을 쓴다(FR-007, 원칙 IV). 묶음 시작 시 후보 전략 전체의 최대 관측 기간(+최근 제외 구간)을 스냅샷에 `warmupTradingDays`로 고정하고 모든 후보와 기준 실행에 동일하게 넘긴다(004 R-15). 004는 이 값이 해당 전략의 요구값보다 작으면 422로 거부하므로 스냅샷 계산이 틀리면 묶음이 시작 단계에서 드러난다.
- 발행 실패(브로커 장애)는 후보를 `PENDING`으로 남기고 다음 시도에 다시 발행한다. `FAILED`로 바꾸지
  않는다 — 계산 실패와 발행 실패는 다르다(FR-011).

## 3. 인메모리 도메인 이벤트 — `backtest` → `paramsearch` (research R6)

`backtest.events` 큐를 다시 소비하지 않는다. 같은 큐에 컨슈머를 하나 더 붙이면 competing consumers라
메시지가 분배되어 두 리스너가 각자 일부만 받는다.

```java
/** backtest 모듈이 결과 저장을 커밋한 뒤 발행한다. */
public record BacktestFinished(
        long backtestJobId,
        String backtestId,
        RunStatus status,       // COMPLETED | FAILED
        String errorCode        // status=FAILED일 때만, 아니면 null
) {}
```

**규약**

- 발행 지점: `BacktestEventService`가 FINISHED 이벤트를 저장하고 **트랜잭션 커밋 후**
  (`@TransactionalEventListener(phase = AFTER_COMMIT)`). 커밋 전에 발행하면 007이 아직 없는 결과를
  읽는다.
- `backtest` 모듈은 `paramsearch`를 알지 못한다. 이벤트 타입만 공개하고 수신자를 모른다.
- 수신자 `BacktestFinishedListener`는 `backtestJobId`로 후보를 찾는다. 못 찾으면 **단일 실행 Job이므로
  경고 없이 무시한다** — 묶음에 속하지 않은 Job이 대부분이다.
- 리스너는 후보 상태를 갱신하고 `CandidateDispatcher`에 다음 후보 발행을 요청한다. 이것이 발행 창의
  전진 지점이다.

## 4. 발행 창(dispatch window) 규약 (research R2)

```text
접수:      유효 후보 N개 생성 → 기준 1건 발행 → 앞의 min(W, N)개 발행 (W = 발행 창)
후보 종료:  BacktestFinished 수신 → 후보 상태 갱신 → PENDING 후보 1개 추가 발행
취소:      PENDING 후보 전부 SKIPPED → 추가 발행 중단 (발행된 후보는 완주)
한도 도달:  같은 처리 + stop_reason 기록
묶음 종료:  PENDING 0 && DISPATCHED 0 → 안정성 계산 → 선정 계산 → 상태 확정
```

**불변식**

1. 한 묶음의 `DISPATCHED` 후보 수는 항상 `W` 이하다. → 한 묶음이 `backtest.jobs`를 독점하지 않는다.
2. 취소·한도 도달 후에는 새 후보가 발행되지 않는다. → 취소가 즉시 효과를 낸다(FR-009).
3. 안정성·선정 계산은 묶음당 정확히 1회다. → 상대 분위(상위 50%)가 중간 상태에서 흔들리지 않는다.
4. `PENDING`에서 `SKIPPED`로 간 후보는 되돌아오지 않는다. → '미실행'이 확정 상태다.

동시 수신 경쟁: 여러 `BacktestFinished`가 동시에 도착하면 묶음 행을 비관적 락으로 잡고 갱신한다.
락 없이 처리하면 `counts` 집계와 발행 창 계산이 서로를 덮어쓴다.

## 5. 실행 측정 기록 (FR-023)

004의 실행 측정 기록에 실행 유형 `AUTO_SEARCH_BATCH`로 남긴다. 007이 새 측정 저장소를 만들지 않는다.

| 항목 | 단위 | 비고 |
|---|---|---|
| 대기 포함 소요 시간 | 후보별·묶음별 | 접수 시각 ~ 종료 시각 |
| 동시 처리 수 | 관측값 | 발행 창 크기가 상한 |
| 자원 사용 | 워커 측 | 워커 변경 없이 기존 기록 사용 |
| 실패 | 건수 | `FAILED` 후보 수. `EXCLUDED`와 구분한다 |
| 건당 원가 | 후보 1건 | 계산·데이터·저장 원가를 구분해 기록(원칙 IX) |

**측정값을 목표로 제시하지 않는다.** 실측 전 처리량 수치를 문서·화면에 쓰지 않는다(원칙 IX).
이 기록이 PD-06의 입력이다.
