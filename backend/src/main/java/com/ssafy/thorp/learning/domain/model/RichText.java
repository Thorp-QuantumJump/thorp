package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 글자 조각 배열(data-model §5-2, research R17). 문단·목록 항목·표 칸처럼 문장 안의 용어 언급과 출처 표시 자리를 구조로 담는 필드다.
 *
 * <p>조각은 일반 텍스트다. 화면은 텍스트 노드로만 그리고 마크업으로 해석하지 않는다(FR-026). V1 글의 문단과 가상의 계산 예는 글자 조각 하나로
 * 담는다(data-model §14).
 *
 * <p>여기서 검사하는 것은 조각 하나의 모양과 "출처 표시는 첫 조각일 수 없다"다. 필드마다 다른 글자 수·조각 수·허용 조각 종류는 블록이 {@link #require}로
 * 검사한다.
 */
public record RichText(List<Run> runs) {

    /** 조각 종류. 필드마다 허용하는 종류가 다르다(data-model §5-3 '조각' 열). */
    public enum RunKind {
        TEXT,
        TERM,
        SOURCE
    }

    /** 글자·용어 언급·출처 표시. */
    public static final Set<RunKind> ALL_KINDS = Set.of(RunKind.TEXT, RunKind.TERM, RunKind.SOURCE);

    /** 글자·용어 언급. 가상의 계산 예가 쓴다. */
    public static final Set<RunKind> TEXT_AND_TERM = Set.of(RunKind.TEXT, RunKind.TERM);

    /** 글자·출처 표시. 목록과 비교 표가 쓴다(FR-026). */
    public static final Set<RunKind> TEXT_AND_SOURCE = Set.of(RunKind.TEXT, RunKind.SOURCE);

    /** 조각 하나. 종류마다 키가 하나뿐이다. */
    public sealed interface Run permits TextRun, TermMentionRun, SourceRun {
        RunKind kind();
    }

    /** 일반 글자. 1자 이상이다. */
    public record TextRun(String text) implements Run {
        public TextRun {
            Objects.requireNonNull(text, "text run must not be null");
            if (text.isEmpty()) {
                throw new IllegalArgumentException("text run must not be empty");
            }
        }

        @Override
        public RunKind kind() {
            return RunKind.TEXT;
        }
    }

    /**
     * 이 자리에 용어가 나온다. 화면이 처음 나오는 자리에 다른 이름과 도움말 표시를 붙인다(research R18).
     *
     * <p>어느 글에도 없는 용어를 가리켜도 저장은 받는다. 게시 전 검사({@code TERM_MENTION_KNOWN})가 막는다.
     *
     * @param text 화면에 보이는 글자 1~50자
     */
    public record TermMentionRun(TermId termId, String text) implements Run {
        public static final int TEXT_MAX_LENGTH = 50;

        public TermMentionRun {
            Objects.requireNonNull(termId, "term mention termId must not be null");
            Objects.requireNonNull(text, "term mention text must not be null");
            if (text.isEmpty() || text.length() > TEXT_MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "term mention text must be 1.." + TEXT_MAX_LENGTH + " characters");
            }
        }

        @Override
        public RunKind kind() {
            return RunKind.TERM;
        }
    }

    /** 바로 앞 글자 옆의 '(*출처)'(FR-007). 인용한 글자 옆이어야 하므로 첫 조각일 수 없다. */
    public record SourceRun(SourceRef source) implements Run {
        public SourceRun {
            Objects.requireNonNull(source, "source run must not be null");
        }

        @Override
        public RunKind kind() {
            return RunKind.SOURCE;
        }
    }

    public RichText {
        Objects.requireNonNull(runs, "runs must not be null");
        runs = List.copyOf(runs);
        if (!runs.isEmpty() && runs.getFirst() instanceof SourceRun) {
            throw new IllegalArgumentException("a source run cannot be the first run");
        }
    }

    /** 글자 조각 하나로 된 값. V1 문단·가상의 계산 예를 담는다. */
    public static RichText text(String text) {
        return new RichText(List.of(new TextRun(text)));
    }

    /** 글자 수. 글자와 용어 언급의 {@code text}를 더한다. 출처 표시는 세지 않는다(data-model §5-2). */
    public int length() {
        int length = 0;
        for (Run run : runs) {
            length += text(run).length();
        }
        return length;
    }

    /** 글자와 용어 언급을 이어 붙인 평문. 출처 표시는 넣지 않는다. 금지 표현·숫자 검사와 조각 구조를 모르는 소비자가 쓴다. */
    public String plainText() {
        StringBuilder plain = new StringBuilder();
        for (Run run : runs) {
            plain.append(text(run));
        }
        return plain.toString();
    }

    /** 글자 조각 하나뿐인가. V1 글은 이 모양만 담는다(R16). */
    public boolean isSingleText() {
        return runs.size() == 1 && runs.getFirst() instanceof TextRun;
    }

    /** 이 필드의 용어 언급. 문서 순서다. */
    public List<TermMentionRun> mentions() {
        return runs.stream()
                .filter(TermMentionRun.class::isInstance)
                .map(TermMentionRun.class::cast)
                .toList();
    }

    /** 이 필드의 출처 표시. 문서 순서다. */
    public List<SourceRun> sources() {
        return runs.stream()
                .filter(SourceRun.class::isInstance)
                .map(SourceRun.class::cast)
                .toList();
    }

    /**
     * 필드 규칙을 검사한다.
     *
     * @param field 위반 문장에 넣을 필드 이름
     * @param minLength 글자 수 하한. 0이면 빈 필드를 허용한다
     * @param maxRuns 조각 수 상한. 상한이 없으면 {@link Integer#MAX_VALUE}
     * @throws IllegalArgumentException 글자 수·조각 수·허용하지 않는 조각 종류
     */
    public void require(
            String field, int minLength, int maxLength, int maxRuns, Set<RunKind> allowed) {
        int length = length();
        if (length < minLength || length > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be " + minLength + ".." + maxLength + " characters: " + length);
        }
        if (runs.size() > maxRuns) {
            throw new IllegalArgumentException(
                    field + " must have at most " + maxRuns + " runs: " + runs.size());
        }
        Set<RunKind> used = runs.stream().map(Run::kind).collect(Collectors.toSet());
        if (!allowed.containsAll(used)) {
            throw new IllegalArgumentException(field + " cannot hold runs of kind " + used);
        }
    }

    private static String text(Run run) {
        return switch (run) {
            case TextRun textRun -> textRun.text();
            case TermMentionRun mention -> mention.text();
            case SourceRun ignored -> "";
        };
    }
}
