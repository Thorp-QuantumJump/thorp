package com.ssafy.thorp.learning.presentation.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * {@code POST /admin/learning/stages/{stageKey}/release} 요청(learning-admin-api §11). 화면은 미리
 * 검사(§10)의 글과 초안 번호를 그대로 싣는다.
 */
public record ReleaseStageRequest(@NotNull @Valid List<@NotNull Article> articles) {

    public record Article(@NotBlank String articleId, @NotNull Integer expectedDraftVersion) {}
}
