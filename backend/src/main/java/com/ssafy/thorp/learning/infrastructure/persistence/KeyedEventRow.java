package com.ssafy.thorp.learning.infrastructure.persistence;

/** 글 키를 붙인 게시 기록 한 줄({@code LearningPublicationEventJpaRepository}의 JPQL 생성자 식). */
public record KeyedEventRow(String articleKey, LearningPublicationEventJpaEntity event) {}
