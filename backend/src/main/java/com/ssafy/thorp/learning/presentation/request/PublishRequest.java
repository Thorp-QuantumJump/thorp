package com.ssafy.thorp.learning.presentation.request;

import jakarta.validation.constraints.NotNull;

/** {@code POST /admin/learning/articles/{articleId}/publish} 요청(learning-admin-api §6). */
public record PublishRequest(@NotNull Integer expectedDraftVersion) {}
