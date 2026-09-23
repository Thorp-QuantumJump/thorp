package com.ssafy.thorp.learning.application.result;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 공개 글 하나(learning-public-api §2). 필드 이름은 계약 JSON과 1:1이다.
 *
 * @param blocks {@code term} 블록에는 같은 판의 용어 이름·문장이 채워져 있다
 * @param example 조회 시점의 대응표로 판정한 예시
 */
public record LearningArticleResult(
        String articleId,
        String title,
        String question,
        int readingOrder,
        List<Block> blocks,
        Example example,
        List<Source> sources,
        Instant publishedAt) {

    public LearningArticleResult {
        blocks = List.copyOf(blocks);
        sources = List.copyOf(sources);
    }

    /**
     * 본문 블록. 종류에 해당하지 않는 필드는 null이다. {@code heading}·{@code paragraph}·{@code hypothetical}은 {@code
     * text}만, {@code term}은 {@code termId}·{@code label}·{@code sentence}만 채운다.
     */
    public record Block(String type, String text, String termId, String label, String sentence) {

        public static Block text(String type, String text) {
            return new Block(type, text, null, null, null);
        }

        public static Block term(String termId, String label, String sentence) {
            return new Block("term", null, termId, label, sentence);
        }
    }

    /**
     * 예시와 연결 가능 여부(research R8).
     *
     * @param available 조회 시점의 열린 대응표에 예시 섹터가 모두 있는가
     * @param missingSectorCodes 대응표에 없는 코드. {@code available=true}면 비어 있다
     * @param unavailableReason {@code available=false}일 때만 값이 있다
     */
    public record Example(
            boolean available,
            List<ExampleSector> sectors,
            List<String> missingSectorCodes,
            UnavailableReason unavailableReason) {

        public Example {
            sectors = List.copyOf(sectors);
            missingSectorCodes = List.copyOf(missingSectorCodes);
            if (available != (unavailableReason == null)) {
                throw new IllegalArgumentException(
                        "unavailableReason must be present exactly when the example is unavailable");
            }
        }
    }

    /**
     * @param name 열린 대응표의 섹터 이름. 대응표에 없거나 대응표를 읽지 못했으면 null
     * @param weightTenths 0.1%p 단위 정수. 판의 값을 바꾸지 않고 그대로 싣는다
     */
    public record ExampleSector(String code, String name, int weightTenths) {
        public ExampleSector {
            Objects.requireNonNull(code, "code must not be null");
        }
    }

    /** 예시로 진단에 들어갈 수 없는 이유. */
    public enum UnavailableReason {
        /** 예시 섹터 가운데 지금 열린 대응표에 없는 코드가 있다. */
        SECTOR_NOT_IN_MAP,
        /** 대응표가 아직 발행되지 않아 판정할 수 없다. */
        SECTOR_MAP_NOT_READY
    }

    /** 출처. {@code url}은 없을 수 있다. */
    public record Source(String label, String url) {}
}
