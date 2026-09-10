package com.ssafy.thorp.paramsearch.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 상태 전이 규칙 검증. data-model.md §3·§4.1이 허용한 전이만 통과하고 나머지는 전부 거부해야 한다.
 *
 * <p>전이 표를 그대로 테스트하는 이유: 허용 목록에 없는 전이를 조용히 통과시키면 묶음의 완료 범위 표시와 재현 기록이 뜻을 잃는다. 특히 {@code SKIPPED}가
 * 되돌아오거나 {@code FAILED} 아닌 상태에서 재시도가 출발하면 "몇 건이 실제로 실패했는가"를 답할 수 없다.
 */
class StatusTransitionTest {

    private static final Instant NOW = Instant.parse("2026-09-10T03:00:00Z");

    @Nested
    @DisplayName("ParamSearchStatus")
    class ParamSearchStatusTransition {

        @Test
        @DisplayName("QUEUED는 RUNNING과 REJECTED로만 간다")
        void queuedGoesToRunningOrRejected() {
            assertThat(allowedFrom(ParamSearchStatus.QUEUED))
                    .containsExactlyInAnyOrder(
                            ParamSearchStatus.RUNNING, ParamSearchStatus.REJECTED);
        }

        @Test
        @DisplayName("RUNNING은 COMPLETED·PARTIAL·FAILED로만 간다")
        void runningGoesToTerminalStates() {
            assertThat(allowedFrom(ParamSearchStatus.RUNNING))
                    .containsExactlyInAnyOrder(
                            ParamSearchStatus.COMPLETED,
                            ParamSearchStatus.PARTIAL,
                            ParamSearchStatus.FAILED);
        }

        @ParameterizedTest
        @EnumSource(
                value = ParamSearchStatus.class,
                names = {"COMPLETED", "PARTIAL", "FAILED", "REJECTED"})
        @DisplayName("최종 상태에서는 어디로도 가지 않는다")
        void finalStatusesAreTerminal(ParamSearchStatus status) {
            assertThat(status.isFinal()).isTrue();
            assertThat(allowedFrom(status)).isEmpty();
        }

        @Test
        @DisplayName("null로의 전이는 거부한다")
        void nullTransitionIsRejected() {
            assertThat(ParamSearchStatus.QUEUED.canTransitionTo(null)).isFalse();
        }

        @Test
        @DisplayName("선정 계산 대상은 COMPLETED와 PARTIAL 둘이다 — 취소된 묶음도 완료 후보로 비교한다")
        void completedAndPartialAreSelectable() {
            assertThat(ParamSearchStatus.COMPLETED.isSelectable()).isTrue();
            assertThat(ParamSearchStatus.PARTIAL.isSelectable()).isTrue();
            assertThat(ParamSearchStatus.FAILED.isSelectable()).isFalse();
            assertThat(ParamSearchStatus.REJECTED.isSelectable()).isFalse();
            assertThat(ParamSearchStatus.QUEUED.isSelectable()).isFalse();
        }

        private List<ParamSearchStatus> allowedFrom(ParamSearchStatus from) {
            return List.of(ParamSearchStatus.values()).stream()
                    .filter(from::canTransitionTo)
                    .toList();
        }
    }

    @Nested
    @DisplayName("CandidateStatus")
    class CandidateStatusTransition {

        @Test
        @DisplayName("PENDING은 DISPATCHED와 SKIPPED로만 간다")
        void pendingGoesToDispatchedOrSkipped() {
            assertThat(allowedFrom(CandidateStatus.PENDING))
                    .containsExactlyInAnyOrder(CandidateStatus.DISPATCHED, CandidateStatus.SKIPPED);
        }

        @Test
        @DisplayName("DISPATCHED는 COMPLETED와 FAILED로만 간다")
        void dispatchedGoesToCompletedOrFailed() {
            assertThat(allowedFrom(CandidateStatus.DISPATCHED))
                    .containsExactlyInAnyOrder(CandidateStatus.COMPLETED, CandidateStatus.FAILED);
        }

        @Test
        @DisplayName("재시도는 FAILED에서 DISPATCHED로만 출발한다 (FR-024)")
        void retryStartsOnlyFromFailed() {
            assertThat(allowedFrom(CandidateStatus.FAILED))
                    .containsExactly(CandidateStatus.DISPATCHED);
        }

        @ParameterizedTest
        @EnumSource(
                value = CandidateStatus.class,
                names = {"COMPLETED", "EXCLUDED", "SKIPPED"})
        @DisplayName("COMPLETED·EXCLUDED·SKIPPED는 되돌아오지 않는 확정 상태다")
        void terminalStatusesDoNotTransition(CandidateStatus status) {
            assertThat(status.isFinal()).isTrue();
            assertThat(allowedFrom(status)).isEmpty();
        }

        @Test
        @DisplayName("SKIPPED는 재시도 대상이 아니다 — DISPATCHED로 되돌아가지 않는다")
        void skippedIsNotRetryable() {
            assertThat(CandidateStatus.SKIPPED.canTransitionTo(CandidateStatus.DISPATCHED))
                    .isFalse();
        }

        @Test
        @DisplayName("FAILED는 재시도가 있어 최종 상태가 아니다")
        void failedIsNotFinal() {
            assertThat(CandidateStatus.FAILED.isFinal()).isFalse();
        }

        @Test
        @DisplayName("EXCLUDED와 SKIPPED는 계산을 시도하지 않은 상태로 구분된다 (원칙 VI)")
        void uncomputedStatusesAreDistinguished() {
            assertThat(CandidateStatus.EXCLUDED.isUncomputed()).isTrue();
            assertThat(CandidateStatus.SKIPPED.isUncomputed()).isTrue();
            assertThat(CandidateStatus.FAILED.isUncomputed()).isFalse();
            assertThat(CandidateStatus.COMPLETED.isUncomputed()).isFalse();
        }

        private List<CandidateStatus> allowedFrom(CandidateStatus from) {
            return List.of(CandidateStatus.values()).stream()
                    .filter(from::canTransitionTo)
                    .toList();
        }
    }

    @Nested
    @DisplayName("ParamSearch 애그리게이트")
    class ParamSearchAggregate {

        @Test
        @DisplayName("접수한 묶음은 QUEUED로 시작하고 식별자는 아직 없다")
        void createdSearchStartsQueued() {
            ParamSearch search = newSearch();

            assertThat(search.getStatus()).isEqualTo(ParamSearchStatus.QUEUED);
            assertThat(search.getId()).isNull();
            assertThat(search.getSearchId()).isNull();
            assertThat(search.getSearchMethod()).isEqualTo(SearchMethod.FULL_GRID);
            assertThat(search.getRandomSeed()).isNull();
            assertThat(search.getEvaluationSplit()).isNull();
        }

        @Test
        @DisplayName("QUEUED → RUNNING → COMPLETED가 통과한다")
        void queuedToRunningToCompleted() {
            ParamSearch running = newSearch().markRunning(NOW);
            assertThat(running.getStartedAt()).isEqualTo(NOW);

            ParamSearch completed = running.markCompleted(NOW.plusSeconds(60));
            assertThat(completed.getStatus()).isEqualTo(ParamSearchStatus.COMPLETED);
            assertThat(completed.getFinishedAt()).isEqualTo(NOW.plusSeconds(60));
        }

        @Test
        @DisplayName("QUEUED에서 바로 COMPLETED로 가면 거부한다")
        void queuedCannotCompleteDirectly() {
            assertThatThrownBy(() -> newSearch().markCompleted(NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QUEUED -> COMPLETED");
        }

        @Test
        @DisplayName("최종 상태에서 다시 전이하면 거부한다")
        void finalSearchCannotTransitionAgain() {
            ParamSearch completed = newSearch().markRunning(NOW).markCompleted(NOW);

            assertThatThrownBy(() -> completed.markPartial(StopReason.CANCELLED, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("PARTIAL은 중단 사유를 반드시 남긴다 (FR-009)")
        void partialRequiresStopReason() {
            ParamSearch running = newSearch().markRunning(NOW);

            assertThatThrownBy(() -> running.markPartial(null, NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("stopReason");

            assertThat(running.markPartial(StopReason.TIME_LIMIT, NOW).getStopReason())
                    .isEqualTo(StopReason.TIME_LIMIT);
        }

        @Test
        @DisplayName("접수 거부는 QUEUED에서만 가능하고 사유를 남긴다 (FR-005b)")
        void rejectRequiresQueuedAndReason() {
            ParamSearch rejected = newSearch().reject(StopReason.CANDIDATE_LIMIT, NOW);
            assertThat(rejected.getStatus()).isEqualTo(ParamSearchStatus.REJECTED);
            assertThat(rejected.getStopReason()).isEqualTo(StopReason.CANDIDATE_LIMIT);

            ParamSearch running = newSearch().markRunning(NOW);
            assertThatThrownBy(() -> running.reject(StopReason.CANDIDATE_LIMIT, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("선정 스냅샷은 COMPLETED·PARTIAL에서만 붙는다")
        void selectionRequiresSelectableStatus() {
            ParamSearch running = newSearch().markRunning(NOW);
            assertThatThrownBy(() -> running.withSelection(Map.of("top", List.of())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COMPLETED or PARTIAL");

            ParamSearch partial = running.markPartial(StopReason.CANCELLED, NOW);
            assertThatCode(() -> partial.withSelection(Map.of("top", List.of())))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("집계 갱신은 상태를 바꾸지 않는다")
        void countsUpdateKeepsStatus() {
            ParamSearch running = newSearch().markRunning(NOW);

            ParamSearch updated = running.withCounts(new SearchCounts(8, 6, 3, 1, 2, 0));

            assertThat(updated.getStatus()).isEqualTo(ParamSearchStatus.RUNNING);
            assertThat(updated.getCounts().completed()).isEqualTo(3);
            assertThat(updated.getCounts().pending()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("SearchCandidate 애그리게이트")
    class SearchCandidateAggregate {

        @Test
        @DisplayName("계산 대상 후보는 PENDING·UNVALIDATED·retryCount 0으로 시작한다")
        void pendingCandidateStartsUnvalidated() {
            SearchCandidate candidate = newCandidate();

            assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.PENDING);
            assertThat(candidate.getValidationLabel()).isEqualTo(ValidationLabel.UNVALIDATED);
            assertThat(candidate.getRetryCount()).isZero();
            assertThat(candidate.getBacktestJobId()).isNull();
            assertThat(candidate.isSelected()).isFalse();
        }

        @Test
        @DisplayName("제외 후보는 Job 없이 사유만 갖는다 (FR-006)")
        void excludedCandidateHasReasonWithoutJob() {
            SearchCandidate excluded =
                    SearchCandidate.excluded(
                            1,
                            "SECTOR_MOMENTUM",
                            ParameterCombination.of(Map.of("topN", 12)),
                            GridCoordinates.of(Map.of("topN", 3)),
                            "TOP_N_EXCEEDS_UNIVERSE",
                            "상위 12개를 고르도록 설정했지만 후보 섹터가 9개입니다");

            assertThat(excluded.getStatus()).isEqualTo(CandidateStatus.EXCLUDED);
            assertThat(excluded.getBacktestJobId()).isNull();
            assertThat(excluded.getReasonCode()).isEqualTo("TOP_N_EXCEEDS_UNIVERSE");
            assertThat(excluded.getStatus().isUncomputed()).isTrue();
        }

        @Test
        @DisplayName("제외 후보는 사유 없이 만들 수 없다 (FR-020)")
        void excludedCandidateRequiresReason() {
            assertThatThrownBy(
                            () ->
                                    SearchCandidate.excluded(
                                            1,
                                            "SECTOR_MOMENTUM",
                                            ParameterCombination.of(Map.of("topN", 12)),
                                            GridCoordinates.of(Map.of("topN", 3)),
                                            "TOP_N_EXCEEDS_UNIVERSE",
                                            "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reasonDetail");
        }

        @Test
        @DisplayName("PENDING → DISPATCHED → COMPLETED가 통과하고 retryCount는 늘지 않는다")
        void firstRunDoesNotIncreaseRetryCount() {
            SearchCandidate completed = newCandidate().markDispatched(100L).markCompleted();

            assertThat(completed.getStatus()).isEqualTo(CandidateStatus.COMPLETED);
            assertThat(completed.getBacktestJobId()).isEqualTo(100L);
            assertThat(completed.getRetryCount()).isZero();
        }

        @Test
        @DisplayName("FAILED → DISPATCHED 재시도는 retryCount를 늘리고 이전 실패 사유를 지운다 (FR-024)")
        void retryIncreasesRetryCountAndClearsReason() {
            SearchCandidate failed =
                    newCandidate().markDispatched(100L).markFailed("DATA_GAP", "자료가 부족합니다");
            assertThat(failed.isRetryable()).isTrue();

            SearchCandidate retried = failed.markDispatched(101L);

            assertThat(retried.getStatus()).isEqualTo(CandidateStatus.DISPATCHED);
            assertThat(retried.getRetryCount()).isEqualTo(1);
            assertThat(retried.getReasonCode()).isNull();
            assertThat(retried.getReasonDetail()).isNull();
            assertThat(retried.getBacktestJobId()).isEqualTo(101L);
        }

        @Test
        @DisplayName("SKIPPED는 재시도할 수 없다 — 되살리면 완료 범위 표시가 뜻을 잃는다")
        void skippedCannotBeRetried() {
            SearchCandidate skipped = newCandidate().markSkipped("CANCELLED", "취소되어 실행되지 않았습니다");

            assertThat(skipped.isRetryable()).isFalse();
            assertThatThrownBy(() -> skipped.markDispatched(200L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SKIPPED -> DISPATCHED");
        }

        @Test
        @DisplayName("COMPLETED 후보는 다시 전이하지 않는다")
        void completedCandidateIsTerminal() {
            SearchCandidate completed = newCandidate().markDispatched(100L).markCompleted();

            assertThatThrownBy(() -> completed.markFailed("DATA_GAP", "자료가 부족합니다"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("발행하지 않은 후보를 완료로 만들 수 없다")
        void pendingCannotCompleteDirectly() {
            assertThatThrownBy(() -> newCandidate().markCompleted())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PENDING -> COMPLETED");
        }

        @Test
        @DisplayName("실패는 사유 코드와 문장을 함께 요구한다 (FR-011)")
        void failureRequiresReason() {
            SearchCandidate dispatched = newCandidate().markDispatched(100L);

            assertThatThrownBy(() -> dispatched.markFailed(null, "자료가 부족합니다"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reasonCode");
        }
    }

    private static ParamSearch newSearch() {
        return ParamSearch.create(
                null,
                List.of("SECTOR_MOMENTUM"),
                List.of("091160.XKRX", "091170.XKRX"),
                "PROVISIONAL-20260910",
                SearchSpace.of(
                        Map.of(
                                "lookbackDays", List.of(20, 60, 120),
                                "topN", List.of(2, 3))),
                newSelectionRule(),
                null,
                Map.of("maxCandidates", 1000),
                Map.of("engineVersion", "0.1.0"),
                SearchCounts.initial(6, 6, 0));
    }

    private static SelectionRule newSelectionRule() {
        return new SelectionRule(
                new SelectionRule.DrawdownFilter("NOT_WORSE_THAN_BASELINE", "EQUAL_WEIGHT"),
                new SelectionRule.StabilityFilter("BATCH_PERCENTILE", 50),
                "NET_RETURN",
                5,
                "SHOW_ALL_TIED",
                new SelectionRule.StabilityFormula(
                        "SINGLE_AXIS_ONE_STEP", "MEAN_ABS_NET_RETURN_DIFF"),
                List.of("displayCount"));
    }

    private static SearchCandidate newCandidate() {
        return SearchCandidate.pending(
                1,
                "SECTOR_MOMENTUM",
                ParameterCombination.of(Map.of("lookbackDays", 60, "topN", 3)),
                GridCoordinates.of(Map.of("lookbackDays", 1, "topN", 1)));
    }
}
