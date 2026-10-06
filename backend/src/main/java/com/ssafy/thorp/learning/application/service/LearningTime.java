package com.ssafy.thorp.learning.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** 배우기 관리 유스케이스가 함께 쓰는 시각 규칙. 글마다 게시와 단계 공개가 같은 값을 써야 판·기록의 시각과 검사 날짜가 같은 기준이 된다. */
final class LearningTime {

    /** 서비스가 운영되는 곳. 게시 전 검사와 '확인 필요'의 기준 날짜가 이 날짜다. */
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private LearningTime() {}

    /** DB가 마이크로초까지 저장하므로 응답 값과 저장 값이 같도록 자른다. */
    static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** 게시 전 검사와 '확인 필요'의 기준 날짜. 서비스가 운영되는 서울 날짜다. */
    static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), SEOUL);
    }
}
