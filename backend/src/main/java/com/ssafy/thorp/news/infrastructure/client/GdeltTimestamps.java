package com.ssafy.thorp.news.infrastructure.client;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * GDELT가 쓰는 시각 표기를 다룬다.
 *
 * <p>조회 매개변수는 {@code yyyyMMddHHmmss}, 응답과 배포 파일 이름은 {@code yyyyMMdd'T'HHmmss'Z'}다. 둘 다 UTC다.
 *
 * <p>여기서 다루는 값은 GDELT가 기록한 시각이지 기사 발행 시각이 아니다. 발행 시각으로 승격하지 않는다(spec FR-012).
 */
final class GdeltTimestamps {

    private static final DateTimeFormatter QUERY_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter SEEN_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private GdeltTimestamps() {}

    /** 조회 매개변수 표기로 만든다. */
    static String format(Instant instant) {
        return QUERY_FORMAT.format(instant);
    }

    /** 배포 파일 이름에 쓰는 표기로 만든다. */
    static String formatSeen(Instant instant) {
        return SEEN_FORMAT.format(instant);
    }

    /** 응답의 기록 시각을 읽는다. 해석할 수 없으면 빈 값이며 다른 시각으로 대체하지 않는다. */
    static Optional<Instant> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.from(SEEN_FORMAT.parse(raw.trim())));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
