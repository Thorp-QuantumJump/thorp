package com.ssafy.thorp.learning.domain.model;

/** 초안 형식(research R16). 형식은 내용 모양에서 추측하지 않고 초안에 명시한다. 새 원고를 넣다 만 초안이 v1 검사로 게시되지 않게 하려는 것이다. */
public enum ArticleFormat {
    /** 9월 판 형식. 블록 네 종류, 문장 하나짜리 용어, 확인 문제 없음. 기존 아홉 글에만 있다(이전 판 고치기). */
    V1,
    /** 새 기준 형식. 블록 열 종류와 글자 조각, 세 문장 용어, 확인 문제. */
    V2
}
