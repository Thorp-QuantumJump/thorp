# 내부 계약: 포트와 인메모리 이벤트

**Date**: 2026-09-10 | **Spec**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md)

007이 다른 모듈·미완성 spec과 만나는 지점을 계약으로 고정한다. RabbitMQ 메시지 계약
(`docs/specs/backtest-messaging/README.md`)은 **변경하지 않는다** — 007은 워커를 바꾸지 않고 기존
작업 메시지를 그대로 쓴다.

---

## 1. `ParameterRegistryPort` — 003과의 경계 (research R1)

003이 소유한 파라미터 등록표를 007이 읽는 유일한 통로다. 003 완성 시 구현체만 교체한다.

```java
public interface ParameterRegistryPort {

    /** 등록표 버전. 임시 구현은 "PROVISIONAL-" 로 시작한다. */
    String version();

    /** 전략의 탐색 축 목록. 순서는 안정적이어야 한다 — 그리드 좌표의 축 순서가 된다. */
    List<ParameterAxis> axes(String strategyId);

    /** 조합 하나가 유효성 제약을 만족하는지. 위반 시 사유 코드를 담아 반환한다. */
    ValidationVerdict validate(String strategyId, ParameterCombination combination, SearchContext context);
}

public record ParameterAxis(
        String name,        // "lookbackDays"
        String label,       // "관측 기간"
        String unit,        // "거래일", null 허용
        List<Object> values // 등록된 후보 값. 인덱스가 그리드 좌표다
) {}

public record ValidationVerdict(boolean valid, String reasonCode, String reasonDetail) {}

/** 유효성 판정에 필요한 묶음 맥락 (섹터 수 등). */
public record SearchContext(int universeSize) {}
```

**규약**

- `axes()`의 반환 순서와 `values`의 인덱스는 같은 `version()` 안에서 안정적이어야 한다. 인덱스가
  흔들리면 `grid_coordinates`가 뜻을 잃고 재현이 깨진다(FR-020).
- `validate()`는 계산 없이 판정할 수 있는 것만 본다. 알려진 위반은 최소 두 가지다 — 모멘텀 선정 수가
  가용 섹터 수 초과, 비중 상한들의 합계로 투자 비중을 채울 수 없음(FR-006).
- 007은 등록표에 없는 값을 절대 만들지 않는다(FR-002). `space`에 등록표 밖 값이 오면 400이다.

**임시 구현 `FixedParameterRegistry`**: `version()`이 `PROVISIONAL-{날짜}`를 돌려주고, 축과 값은
코드 상수로 둔다. PD-02가 확정되지 않았으므로 **이 값은 팀 확정값이 아니다** — API 응답의
`registryVersion`과 화면 표시로 임시임을 밝힌다.

## 2. `CandidateJobLauncher` — 004와의 경계

후보 1건과 동일 비중 기준을 기존 백테스트 실행 경로로 보낸다. 007은 `EngineInput`을 직접 만들지
않고 `backtest` 모듈의 접수 유스케이스를 호출한다.

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
