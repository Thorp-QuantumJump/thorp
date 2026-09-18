package com.ssafy.thorp.learning.presentation.response;

/**
 * {@code GET /admin/learning/access}의 {@code data}(learning-admin-api §1). 늘 {@code admin: true}다.
 *
 * <p>권한이 없으면 404이지 {@code admin: false}로 200을 주지 않는다. 화면은 200일 때만 관리 화면을 그린다.
 */
public record AdminAccessResponse(boolean admin) {

    public static AdminAccessResponse granted() {
        return new AdminAccessResponse(true);
    }
}
