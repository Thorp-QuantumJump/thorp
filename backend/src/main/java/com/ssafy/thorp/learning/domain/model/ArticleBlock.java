package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Objects;

/**
 * 글 본문 블록(data-model §4). 네 종류만 있고 {@code sealed}라 다섯 번째 종류를 만들 수 없다.
 *
 * <p>{@code text}는 일반 텍스트다. 화면은 텍스트 노드로만 그리고 HTML로 해석하지 않는다(research R3).
 */
public sealed interface ArticleBlock
        permits ArticleBlock.HeadingBlock,
                ArticleBlock.ParagraphBlock,
                ArticleBlock.TermBlock,
                ArticleBlock.HypotheticalBlock {

    /** 한 글에 둘 수 있는 블록 수의 상한. */
    int MAX_BLOCKS = 60;

    /** 소제목. {@code text} 1~100자. */
    record HeadingBlock(String text) implements ArticleBlock {
        public static final int MAX_LENGTH = 100;

        public HeadingBlock {
            requireLength(text, MAX_LENGTH, "heading");
        }
    }

    /** 문단. {@code text} 1~2,000자. */
    record ParagraphBlock(String text) implements ArticleBlock {
        public static final int MAX_LENGTH = 2000;

        public ParagraphBlock {
            requireLength(text, MAX_LENGTH, "paragraph");
        }
    }

    /**
     * 용어 설명 자리. 문장은 블록에 담지 않고 용어 설명에서 온다. 같은 문장을 두 곳에 두면 도움말과 본문이 갈라진다(FR-008).
     *
     * <p>이 글에 없는 용어를 가리켜도 여기서는 거절하지 않는다. 게시 전 검사({@code TERM_BLOCKS_MATCH})가 막는다(data-model §4).
     */
    record TermBlock(TermId termId) implements ArticleBlock {
        public TermBlock {
            Objects.requireNonNull(termId, "term block termId must not be null");
        }
    }

    /** 가상의 계산 예. 결과성 숫자는 이 블록 안에만 쓸 수 있다(FR-005). {@code text} 1~1,000자. */
    record HypotheticalBlock(String text) implements ArticleBlock {
        public static final int MAX_LENGTH = 1000;

        public HypotheticalBlock {
            requireLength(text, MAX_LENGTH, "hypothetical");
        }
    }

    private static void requireLength(String text, int max, String type) {
        Objects.requireNonNull(text, type + " block text must not be null");
        if (text.isEmpty() || text.length() > max) {
            throw new IllegalArgumentException(
                    type + " block text must be 1.." + max + " characters: " + text.length());
        }
    }
}
