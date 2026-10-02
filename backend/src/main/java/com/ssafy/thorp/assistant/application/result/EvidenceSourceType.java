package com.ssafy.thorp.assistant.application.result;

/** 용어 근거의 원천 넷(spec 017 FR-003, data-model §3). 결과 화면 근거는 출처가 없다(null). */
public enum EvidenceSourceType {
    /** 013 게시 용어 설명({@code learning_published_term}). */
    TERM,
    /** 013 게시 글의 판 블록. */
    ARTICLE,
    /** 003 파라미터 등록표. */
    REGISTRY,
    /** 001 섹터 목록. */
    SECTOR
}
