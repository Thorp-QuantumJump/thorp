package com.ssafy.thorp.learning.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 제도 안내 '확인 필요'의 경계(research R37, tasks T160). 날짜를 고정해 179·180·181일을 본다. */
class InstitutionNoteReviewPeriodTest {

    private static final InstitutionNoteReviewPeriod PERIOD = new InstitutionNoteReviewPeriod(180);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    @Test
    @DisplayName("180일이면 179·180일 전은 확인 필요가 아니고 181일 전부터 확인 필요다")
    void boundary() {
        assertThat(PERIOD.overdue(TODAY.minusDays(179), TODAY)).isFalse();
        assertThat(PERIOD.overdue(TODAY.minusDays(180), TODAY)).isFalse();
        assertThat(PERIOD.overdue(TODAY.minusDays(181), TODAY)).isTrue();
        assertThat(PERIOD.overdue(TODAY, TODAY)).isFalse();
    }

    @Test
    @DisplayName("기간은 1일 이상이다")
    void positiveDays() {
        assertThatThrownBy(() -> new InstitutionNoteReviewPeriod(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
