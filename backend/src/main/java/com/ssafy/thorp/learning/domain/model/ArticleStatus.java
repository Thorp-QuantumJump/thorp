package com.ssafy.thorp.learning.domain.model;

/** 글의 게시 상태(data-model §7). 삭제 상태는 없다(spec Out of Scope). */
public enum ArticleStatus {
    /** 일반 사용자에게 보이지 않는다. */
    DRAFT,
    /** 공개 중인 판이 있다. */
    PUBLISHED
}
