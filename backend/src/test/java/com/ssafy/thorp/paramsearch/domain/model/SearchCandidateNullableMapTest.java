package com.ssafy.thorp.paramsearch.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ssafy.thorp.paramsearch.domain.vo.GridCoordinates;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 후보가 들고 다니는 맵은 <b>값이 없다는 사실 자체가 뜻</b>이다.
 *
 * <p>안정성은 이웃이 없으면 {@code value}가 null이고(FR-013b), 선정 사유는 계산되지 않은 후보에서 {@code code}가 null이다. 그래서 방어
 * 복사가 null 값을 담을 수 있어야 한다 — {@code Map.copyOf}는 null 값을 거부한다.
 *
 * <p>이 테스트가 생긴 이유: 이웃이 없는 후보가 하나라도 있는 묶음에서 선정 저장이 {@code NullPointerException}으로 죽어 <b>묶음이 끝나지
 * 않았다.</b> 후보가 하나뿐이거나 대부분이 실패한 묶음이 정확히 그 경우다. 그런 묶음은 화면에서 영원히 "실행 중"으로 남는다.
 */
class SearchCandidateNullableMapTest {

    @Test
    @DisplayName("계산 불가 안정성을 붙여도 죽지 않고 value가 null로 남는다")
    void uncalculableStabilityKeepsItsNullValue() {
        SearchCandidate candidate = pending();

        SearchCandidate withStability =
                assertThatNoThrow(
                        () -> candidate.withStability(StabilityScore.uncalculable().toMap()));

        assertThat(withStability.getStability()).containsEntry("calculable", false);
        assertThat(withStability.getStability())
                .as("키를 지우면 화면이 '없음'과 '필드가 아예 없음'을 구분할 수 없다")
                .containsKey("value");
        assertThat(withStability.getStability().get("value")).isNull();
    }

    @Test
    @DisplayName("사유에 null 항목이 있어도 그대로 담긴다")
    void selectionReasonKeepsNullEntries() {
        Map<String, Object> reason = new HashMap<>();
        reason.put("outcome", "NOT_EVALUATED");
        // 계산되지 않은 후보는 사유 코드가 없을 수 있다. 0이나 빈 문자열로 채우면 사유가 있는 척이 된다.
        reason.put("code", null);

        SearchCandidate selected = assertThatNoThrow(() -> pending().withSelection(false, reason));

        assertThat(selected.getSelectionReason()).containsKey("code");
        assertThat(selected.getSelectionReason().get("code")).isNull();
    }

    @Test
    @DisplayName("붙인 맵은 밖에서 바꿀 수 없다")
    void theStoredMapIsNotWritable() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("calculable", false);
        mutable.put("value", null);
        SearchCandidate candidate = pending().withStability(mutable);

        mutable.put("value", "0.99");

        assertThat(candidate.getStability().get("value")).as("방어 복사가 없으면 저장된 뒤에도 값이 바뀐다").isNull();
    }

    private static <T> T assertThatNoThrow(java.util.function.Supplier<T> action) {
        assertThatCode(action::get).doesNotThrowAnyException();
        return action.get();
    }

    private static SearchCandidate pending() {
        return SearchCandidate.pending(
                1,
                "TEST_STRATEGY",
                ParameterCombination.of(Map.of("x", 1)),
                GridCoordinates.of(Map.of("x", 0)));
    }
}
