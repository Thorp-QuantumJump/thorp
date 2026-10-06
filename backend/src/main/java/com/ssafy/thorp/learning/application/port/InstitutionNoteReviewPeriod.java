package com.ssafy.thorp.learning.application.port;

import java.time.LocalDate;

/**
 * 제도 안내 '확인 필요'의 기간(research R37). 설정 {@code thorp.learning.institution-note-review-days}에서 읽어 온 불변
 * 사본이다.
 *
 * <p>설정 클래스를 서비스가 직접 주입받지 않는 이유: 그 클래스는 {@code infrastructure.config}에 있고 application은
 * infrastructure를 참조할 수 없다({@code ArchitectureTest} 계층 규칙). {@code MyRecordLimits}와 같은 자리다.
 *
 * @param days 기준 시점이 오늘보다 이 일수 넘게 지났으면 확인이 필요하다
 */
public record InstitutionNoteReviewPeriod(int days) {

    public InstitutionNoteReviewPeriod {
        if (days < 1) {
            throw new IllegalArgumentException("institution note review days must be positive");
        }
    }

    /** 기준 시점 {@code asOf}가 오늘보다 {@link #days}일 넘게 지났는가. 180일이면 179·180일 전은 아니고 181일 전은 그렇다. */
    public boolean overdue(LocalDate asOf, LocalDate today) {
        return asOf.plusDays(days).isBefore(today);
    }
}
