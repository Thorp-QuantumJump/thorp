package com.ssafy.thorp.learning.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 배우기 설정(spec 013). {@code thorp.learning.*}
 *
 * <p>관리 계정 허용 목록({@code admin-user-ids})은 {@code ConfiguredLearningAdminPolicy}가 따로 읽는다. 여기 두지 않은
 * 키는 무시한다.
 *
 * <p>{@code @NotNull}이라 값이 비면 애플리케이션이 기동에 실패한다. 설정 누락이 "확인 필요 표시 없음"으로 바뀌지 않게 하려는 것이다. {@code
 * MyRecordProperties}와 같은 규약이다.
 */
@Validated
@ConfigurationProperties(prefix = "thorp.learning")
public class LearningProperties {

    /** 공개 판의 제도 안내 기준 시점이 이 일수보다 오래되면 관리 목록에 '확인 필요'를 보인다(research R37). */
    @NotNull
    @Min(1)
    private Integer institutionNoteReviewDays;

    public Integer getInstitutionNoteReviewDays() {
        return institutionNoteReviewDays;
    }

    public void setInstitutionNoteReviewDays(Integer institutionNoteReviewDays) {
        this.institutionNoteReviewDays = institutionNoteReviewDays;
    }
}
