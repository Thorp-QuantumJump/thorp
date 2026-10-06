package com.ssafy.thorp.assistant.application.result;

/**
 * 도우미가 붙는 화면 종류(spec 015 Key Entities "페이지 맥락", spec 017 FR-001). 종류마다 따로 켠다.
 *
 * <p>{@code RESULT}만 식별자(공개 토큰)를 갖는다. 진단 두 종류는 식별자가 없고 화면 선택값도 받지 않는다(017 FR-006).
 */
public enum PageKind {
    RESULT,
    SECTOR_SELECTION,
    STRATEGY_SETTINGS;

    /** 이 종류의 요청이 공개 토큰을 가져야 하는가. 아니면 토큰이 있는 요청을 거절한다. */
    public boolean requiresPublicToken() {
        return this == RESULT;
    }
}
