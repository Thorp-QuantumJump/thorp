package com.ssafy.thorp.news.application.command;

import com.ssafy.thorp.news.application.exception.NewsQueryException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * 기사 메타데이터 내보내기 요청(spec FR-017, contracts/openapi.yaml).
 *
 * <p>묶음 조회와 달리 <b>기간이 필수</b>다. 내보내기는 한 번에 많은 행을 가져가는 경로라 범위를 정하지 않으면 저장된 전부를 뜨게 된다.
 *
 * <p>{@code cursor}는 이전 페이지의 마지막 항목을 가리킨다. offset을 쓰지 않는 이유는 페이지를 넘기는 사이에 수집이 돌아 행이 늘면 offset이 가리키는
 * 자리가 밀려 같은 기사를 두 번 받거나 건너뛰기 때문이다. 기준 시각과 기사 식별자로 자리를 잡으면 행이 늘어도 그 자리는 그대로다.
 *
 * @param sectorId 좁힐 섹터. 없으면 전체다. 식별자는 spec 001이 소유한다.
 * @param from 구간 시작. 경계를 포함한다.
 * @param to 구간 끝. 경계를 포함한다.
 * @param cursor 이전 페이지의 마지막 자리. 첫 페이지면 null.
 * @param limit 한 페이지의 최대 항목 수.
 */
public record NewsArticleExportQuery(
        String sectorId, Instant from, Instant to, Cursor cursor, int limit) {

    /** 계약이 정한 페이지 상한. 이보다 크게 요청하면 거부한다. */
    public static final int MAX_LIMIT = 500;

    /** 요청이 페이지 크기를 말하지 않았을 때 쓰는 값. */
    public static final int DEFAULT_LIMIT = 100;

    public NewsArticleExportQuery {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new NewsQueryException("조회 시작 시각이 종료 시각보다 늦습니다.");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new NewsQueryException("페이지 크기는 1 이상 " + MAX_LIMIT + " 이하여야 합니다.");
        }
    }

    /**
     * 요청 값에서 만든다. 기간을 한쪽만 주면 거부한다.
     *
     * @param maxRangeDays 요청 구간 상한(일). 묶음 조회와 같은 경계를 쓴다.
     */
    public static NewsArticleExportQuery of(
            String sectorId,
            Instant from,
            Instant to,
            String rawCursor,
            Integer rawLimit,
            int maxRangeDays) {
        if (from == null || to == null) {
            throw new NewsQueryException("내보내기는 from과 to를 함께 지정해야 합니다.");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(maxRangeDays)) > 0) {
            throw new NewsQueryException("조회 구간은 최대 " + maxRangeDays + "일입니다.");
        }
        return new NewsArticleExportQuery(
                sectorId,
                from,
                to,
                Cursor.parse(rawCursor).orElse(null),
                rawLimit == null ? DEFAULT_LIMIT : rawLimit);
    }

    public boolean hasSector() {
        return sectorId != null && !sectorId.isBlank();
    }

    /**
     * 페이지 자리.
     *
     * <p>바깥에는 불투명한 문자열로 나간다. 안에 든 값을 계약으로 삼으면 나중에 정렬 기준을 바꿀 때 이미 나간 cursor를 해석할 수 없게 되고, 내부 구조를 밖에서
     * 만들어 보내는 일도 생긴다.
     *
     * <p>기준 시각 하나로는 자리를 잡을 수 없다. 같은 시각의 기사가 여럿이면 어디까지 보냈는지 구별되지 않아 그 기사들이 끝없이 되풀이된다. 기사 식별자를 함께 둬야
     * 자리가 하나로 정해진다.
     *
     * @param orderingInstant 정렬 기준 시각.
     * @param articleId 같은 시각 안에서의 자리.
     */
    public record Cursor(Instant orderingInstant, String articleId) {

        private static final String SEPARATOR = "|";

        public String encode() {
            String raw = orderingInstant.toEpochMilli() + SEPARATOR + articleId;
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        /**
         * 받은 cursor를 해석한다.
         *
         * <p>해석할 수 없으면 거부한다. 조용히 첫 페이지로 되돌리면 클라이언트가 같은 페이지를 끝없이 받으면서도 그 사실을 모른다.
         */
        public static Optional<Cursor> parse(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return Optional.empty();
            }
            try {
                String raw =
                        new String(
                                Base64.getUrlDecoder().decode(encoded),
                                java.nio.charset.StandardCharsets.UTF_8);
                int separator = raw.lastIndexOf(SEPARATOR);
                if (separator <= 0 || separator == raw.length() - 1) {
                    throw new NewsQueryException("cursor를 해석할 수 없습니다.");
                }
                return Optional.of(
                        new Cursor(
                                Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))),
                                raw.substring(separator + 1)));
            } catch (IllegalArgumentException e) {
                // 받은 값을 메시지에 싣지 않는다(spec FR-021).
                throw new NewsQueryException("cursor를 해석할 수 없습니다.");
            }
        }
    }
}
