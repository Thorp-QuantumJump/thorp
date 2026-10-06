package com.ssafy.thorp.assistant.application.result;

/** 도구 인자 종류(spec 017 research R2). */
public enum ToolArgumentType {
    /** {@code YYYY-MM-DD}. 015 결과 도구의 인자. */
    DATE,
    /** 요청 시점에 서버가 만든 닫힌 집합 안의 값. 공개 콘텐츠 키({@code articleId}·{@code strategyId})다. */
    ENUM
}
