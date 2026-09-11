package com.ssafy.thorp.paramsearch.application.port;

/**
 * 조합 하나에 대한 등록표의 유효성 판정(contracts/param-search-internal.md §1, FR-006).
 *
 * <p><b>{@code reasonCode}는 등록표가 돌려준 값을 그대로 저장하고 표시한다.</b> 007은 위반 코드 목록을 상수로 두지 않고 코드의 뜻도 해석하지 않는다
 * — 제약의 종류는 003이 소유한다(FR-025).
 *
 * @param valid 이 조합을 계산 대상으로 삼을 수 있는가
 * @param reasonCode 위반 사유 코드. 유효하면 null
 * @param reasonDetail 사용자에게 보여줄 사유 문구. 유효하면 null
 */
public record ValidationVerdict(boolean valid, String reasonCode, String reasonDetail) {

    public ValidationVerdict {
        if (valid && (reasonCode != null || reasonDetail != null)) {
            throw new IllegalArgumentException("valid verdict must not carry a reason");
        }
        // 사유 없는 제외는 화면에서 왜 빠졌는지를 설명할 수 없다(원칙 VI).
        if (!valid && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("invalid verdict must carry a reasonCode");
        }
    }

    /** 이름이 {@code valid()}가 아닌 이유: record의 접근자와 이름이 겹쳐 컴파일되지 않는다. */
    public static ValidationVerdict ok() {
        return new ValidationVerdict(true, null, null);
    }

    public static ValidationVerdict violation(String reasonCode, String reasonDetail) {
        return new ValidationVerdict(false, reasonCode, reasonDetail);
    }
}
