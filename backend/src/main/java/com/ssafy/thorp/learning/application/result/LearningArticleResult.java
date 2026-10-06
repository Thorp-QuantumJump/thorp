package com.ssafy.thorp.learning.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 공개 글 하나(learning-public-api §3). 관리 미리 보기(learning-admin-api §8)도 같은 모양이다. 필드 이름은 계약 JSON과 1:1이다.
 *
 * <p>{@code LEGACY} 판의 블록은 9월 판 모양({@link TextBlock}·{@link LegacyTermBlock})이고, {@code CURRENT} 판은
 * V2 블록과 글자 조각이다. 배포 순서 호환을 위해 9월 판 필드의 모양과 뜻은 바꾸지 않는다(contracts/README).
 *
 * @param edition {@code CURRENT}·{@code PRE_RELEASE}·{@code PREVIOUS}
 * @param stage 공개 배치의 단계. 미리 보기는 초안 배치의 단계다
 * @param number 단계 안 번호(예: {@code 2-3}). 공개된 글({@code CURRENT})만 있다
 * @param minDwellSeconds {@code LEGACY} 판은 기록하지 않는 글이라 null이다
 * @param quiz {@code LEGACY} 판은 비어 있다
 * @param example 예시가 없는 V2 글은 null이다. 있으면 조회 시점의 대응표로 판정한다
 * @param next 읽는 순서상 다음 공개된 글. 없거나 {@code LEGACY} 판이면 null이다
 * @param stageArticleCount 그 단계의 공개된 글 수. {@code LEGACY} 판이면 null이다
 * @param hasUnreleasedStages 공개되지 않은 단계가 있는가
 * @param preview 관리 미리 보기인가. 공개 응답은 false다
 */
public record LearningArticleResult(
        String articleId,
        String edition,
        String title,
        String question,
        int readingOrder,
        StageRef stage,
        String number,
        int readingMinutes,
        Integer minDwellSeconds,
        List<Block> blocks,
        List<QuizView> quiz,
        Example example,
        List<Source> sources,
        NextArticle next,
        Integer stageArticleCount,
        boolean hasUnreleasedStages,
        Instant publishedAt,
        boolean preview) {

    public LearningArticleResult {
        blocks = List.copyOf(blocks);
        quiz = List.copyOf(quiz);
        sources = List.copyOf(sources);
    }

    /** 단계. */
    public record StageRef(String stageKey, int number, String name) {}

    /** 읽는 순서상 다음 공개된 글. */
    public record NextArticle(String articleId, String number, String title) {}

    // --- 블록 ---

    /**
     * 본문 블록. 종류마다 다른 레코드이고 {@link #type()}이 계약의 {@code type} 값이다.
     *
     * <p>{@link #plainText()}는 블록에 보이는 글자를 이어 붙인 평문이다. 017처럼 글을 근거로 읽는 소비자가 글자 조각 구조를 몰라도 되게 한다
     * (tasks ⑧). 출처 표시와 표의 구조는 담지 않는다.
     */
    public sealed interface Block
            permits TextBlock,
                    LegacyTermBlock,
                    KeySummaryView,
                    ParagraphView,
                    TermView,
                    HypotheticalView,
                    MisconceptionView,
                    ListView,
                    TableView,
                    DiagramView,
                    InstitutionNoteView {

        String type();

        String plainText();

        /** 글자와 용어 언급을 이어 붙인다. 출처 표시는 평문에 넣지 않는다. */
        static String join(List<RunView> runs) {
            StringBuilder text = new StringBuilder();
            for (RunView run : runs) {
                switch (run) {
                    case TextRunView textRun -> text.append(textRun.text());
                    case MentionRunView mention -> text.append(mention.text());
                    case SourceRunView ignored -> {
                        // 출처 표시는 평문에 넣지 않는다.
                    }
                }
            }
            return text.toString();
        }
    }

    /** 글자 하나뿐인 블록. 소제목과 9월 판의 문단·가상의 계산 예다. */
    public record TextBlock(String type, String text) implements Block {
        @Override
        public String plainText() {
            return text;
        }
    }

    /** 9월 판 용어 블록. 이름과 설명 문장이 같은 판의 용어에서 채워져 있다. */
    public record LegacyTermBlock(String termId, String label, String sentence) implements Block {
        @Override
        public String type() {
            return "term";
        }

        @Override
        public String plainText() {
            return label + " " + sentence;
        }
    }

    public record KeySummaryView(List<List<RunView>> lines) implements Block {
        @Override
        public String type() {
            return "keySummary";
        }

        @Override
        public String plainText() {
            return lines.stream().map(Block::join).collect(Collectors.joining("\n"));
        }
    }

    public record ParagraphView(List<RunView> content) implements Block {
        @Override
        public String type() {
            return "paragraph";
        }

        @Override
        public String plainText() {
            return Block.join(content);
        }
    }

    /** V2 용어 블록. 이름·다른 이름·세 문장이 같은 판의 용어에서 채워져 있다. {@code meaning}이 도움말 문장과 같은 값이다(SC-003). */
    public record TermView(
            String termId,
            String label,
            List<String> aliases,
            String meaning,
            String reading,
            String caveat)
            implements Block {
        @Override
        public String type() {
            return "term";
        }

        @Override
        public String plainText() {
            return String.join(" ", label, meaning, reading, caveat);
        }
    }

    public record HypotheticalView(List<RunView> content) implements Block {
        @Override
        public String type() {
            return "hypothetical";
        }

        @Override
        public String plainText() {
            return Block.join(content);
        }
    }

    public record MisconceptionView(List<RunView> wrong, List<RunView> correction)
            implements Block {
        @Override
        public String type() {
            return "misconception";
        }

        @Override
        public String plainText() {
            return Block.join(wrong) + "\n" + Block.join(correction);
        }
    }

    public record ListView(boolean ordered, List<List<RunView>> items) implements Block {
        @Override
        public String type() {
            return "list";
        }

        @Override
        public String plainText() {
            return items.stream().map(Block::join).collect(Collectors.joining("\n"));
        }
    }

    public record TableView(String caption, List<String> columns, List<List<List<RunView>>> rows)
            implements Block {
        @Override
        public String type() {
            return "table";
        }

        @Override
        public String plainText() {
            return rows.stream()
                    .map(row -> row.stream().map(Block::join).collect(Collectors.joining(" ")))
                    .collect(Collectors.joining("\n"));
        }
    }

    public record DiagramView(
            String kind,
            String title,
            String unit,
            List<String> categories,
            List<SeriesView> series,
            String description)
            implements Block {
        @Override
        public String type() {
            return "diagram";
        }

        @Override
        public String plainText() {
            return description;
        }
    }

    public record SeriesView(String name, List<BigDecimal> values) {}

    /**
     * @param asOf 공개 판에서는 늘 있다. 미리 보기에서는 초안 값이라 null일 수 있다
     * @param source 공개 판에서는 늘 있다. 미리 보기에서는 null일 수 있다
     */
    public record InstitutionNoteView(List<RunView> content, LocalDate asOf, Source source)
            implements Block {
        @Override
        public String type() {
            return "institutionNote";
        }

        @Override
        public String plainText() {
            return Block.join(content);
        }
    }

    // --- 글자 조각 ---

    /** 글자 조각. */
    public sealed interface RunView permits TextRunView, MentionRunView, SourceRunView {}

    public record TextRunView(String text) implements RunView {}

    /**
     * 용어 언급. 화면이 처음 나오는 자리에 다른 이름과 도움말 표시를 둔다(R18).
     *
     * @param owned 이 글이 소유한 용어인가
     * @param aliases 이 글의 용어면 판에서, 다른 글의 용어면 게시 용어 색인에서 온다. 그 용어가 게시되지 않았으면 비어 있다
     */
    public record MentionRunView(String term, String text, boolean owned, List<String> aliases)
            implements RunView {
        public MentionRunView {
            aliases = List.copyOf(aliases);
        }
    }

    public record SourceRunView(Source source) implements RunView {}

    // --- 확인 문제 ---

    /** 확인 문제. 정답과 풀이를 싣는다. 화면은 답한 뒤(로그인은 기록을 저장한 뒤)에만 보인다(R22). */
    public record QuizView(
            String questionId,
            String prompt,
            List<ChoiceView> choices,
            String correctChoiceId,
            String explanation) {}

    public record ChoiceView(String choiceId, String text) {}

    // --- 예시·출처 ---

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

    /** 출처. 9월 판 출처 목록은 {@code url}이 없을 수 있다. */
    public record Source(String label, String url) {}
}
