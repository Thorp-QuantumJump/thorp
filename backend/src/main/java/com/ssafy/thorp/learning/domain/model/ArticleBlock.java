package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.RichText.RunKind;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 글 본문 블록(data-model §5). {@code sealed}라 정한 종류 밖의 블록을 만들 수 없다.
 *
 * <p>9월 판(V1) 블록은 {@code heading}·{@code paragraph}·{@code term}·{@code hypothetical} 네 종류이고 문단과
 * 가상의 계산 예는 글자 조각 하나다. 2판(V2) 글은 열 종류를 모두 쓴다. 어느 형식의 초안에 둘 수 있는지는 {@link #allowedIn}이 가른다(research
 * R16).
 *
 * <p>여기서 거절하는 것은 <b>저장할 수 없는 모양</b>이다(data-model §5-3의 길이·개수·조각 종류). 게시해도 되는 글인지는 게시 전 검사가 가른다. 블록과
 * 조각의 글자는 일반 텍스트이고 화면은 텍스트 노드로만 그린다(research R3·R17).
 */
public sealed interface ArticleBlock
        permits ArticleBlock.KeySummaryBlock,
                ArticleBlock.HeadingBlock,
                ArticleBlock.ParagraphBlock,
                ArticleBlock.TermBlock,
                ArticleBlock.HypotheticalBlock,
                ArticleBlock.MisconceptionBlock,
                ArticleBlock.ListBlock,
                ArticleBlock.TableBlock,
                ArticleBlock.DiagramBlock,
                ArticleBlock.InstitutionNoteBlock {

    /** V1 글 하나에 둘 수 있는 블록 수의 상한. */
    int MAX_BLOCKS = 60;

    /** V2 글 하나에 둘 수 있는 블록 수의 상한. */
    int MAX_BLOCKS_V2 = 80;

    /** 이 형식의 글에 둘 수 있는 블록 수의 상한. */
    static int maxBlocks(ArticleFormat format) {
        return format == ArticleFormat.V1 ? MAX_BLOCKS : MAX_BLOCKS_V2;
    }

    /**
     * 이 블록을 그 형식의 초안에 둘 수 있는가. V2는 모든 블록을 받는다. V1은 9월 판 네 종류만 받고, 문단과 가상의 계산 예는 글자 조각 하나여야 한다(용어
     * 언급·출처 표시는 V2의 요소다).
     */
    static boolean allowedIn(ArticleBlock block, ArticleFormat format) {
        if (format == ArticleFormat.V2) {
            return true;
        }
        return switch (block) {
            case HeadingBlock ignored -> true;
            case TermBlock ignored -> true;
            case ParagraphBlock paragraph -> paragraph.content().isSingleText();
            case HypotheticalBlock hypothetical -> hypothetical.content().isSingleText();
            case KeySummaryBlock ignored -> false;
            case MisconceptionBlock ignored -> false;
            case ListBlock ignored -> false;
            case TableBlock ignored -> false;
            case DiagramBlock ignored -> false;
            case InstitutionNoteBlock ignored -> false;
        };
    }

    /** 이 블록의 글자 조각 필드. 문서 순서(필드 순서 → 줄·칸 순서)다. 조각 필드가 없으면 비어 있다. */
    default List<RichTextField> richTextFields() {
        return List.of();
    }

    /**
     * 블록 안의 글자 조각 필드 하나.
     *
     * @param field 블록 안 위치. 예: {@code content}, {@code lines[1]}, {@code wrong}, {@code items[2]},
     *     {@code rows[0][1]}(data-model §8-4)
     */
    record RichTextField(String field, RichText text) {}

    /** 글 맨 위 '핵심 요약' 상자. 줄 0~3개, 줄마다 1~120자. 세 줄인지는 게시 전 검사({@code KEY_SUMMARY})가 본다. */
    record KeySummaryBlock(List<RichText> lines) implements ArticleBlock {
        public static final int MAX_LINES = 3;
        public static final int LINE_MAX_LENGTH = 120;

        public KeySummaryBlock {
            lines = List.copyOf(lines);
            if (lines.size() > MAX_LINES) {
                throw new IllegalArgumentException(
                        "keySummary must have at most " + MAX_LINES + " lines: " + lines.size());
            }
            for (RichText line : lines) {
                line.require(
                        "keySummary line",
                        1,
                        LINE_MAX_LENGTH,
                        Integer.MAX_VALUE,
                        RichText.ALL_KINDS);
            }
        }

        @Override
        public List<RichTextField> richTextFields() {
            List<RichTextField> fields = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                fields.add(new RichTextField("lines[" + index + "]", lines.get(index)));
            }
            return fields;
        }
    }

    /** 소제목. {@code text} 1~100자. */
    record HeadingBlock(String text) implements ArticleBlock {
        public static final int MAX_LENGTH = 100;

        public HeadingBlock {
            requireLength(text, 1, MAX_LENGTH, "heading text");
        }
    }

    /** 문단. 1~2,000자, 조각 40개 이하. V1 문단은 글자 조각 하나다. */
    record ParagraphBlock(RichText content) implements ArticleBlock {
        public static final int MAX_LENGTH = 2000;
        public static final int MAX_RUNS = 40;

        public ParagraphBlock {
            Objects.requireNonNull(content, "paragraph content must not be null");
            content.require("paragraph", 1, MAX_LENGTH, MAX_RUNS, RichText.ALL_KINDS);
        }

        /** 글자 조각 하나로 된 문단(V1). */
        public ParagraphBlock(String text) {
            this(RichText.text(text));
        }

        @Override
        public List<RichTextField> richTextFields() {
            return List.of(new RichTextField("content", content));
        }
    }

    /**
     * 용어 설명 자리. 문장은 블록에 담지 않고 용어 설명에서 온다. 같은 문장을 두 곳에 두면 도움말과 본문이 갈라진다(FR-008).
     *
     * <p>이 글에 없는 용어를 가리켜도 여기서는 거절하지 않는다. 게시 전 검사({@code TERM_BLOCKS_MATCH})가 막는다.
     */
    record TermBlock(TermId termId) implements ArticleBlock {
        public TermBlock {
            Objects.requireNonNull(termId, "term block termId must not be null");
        }
    }

    /** 가상의 계산 예. 결과성 숫자는 이 블록 같은 가상의 예 요소에만 쓸 수 있다(FR-005). 1~1,000자, 글자와 용어 언급만 담는다. */
    record HypotheticalBlock(RichText content) implements ArticleBlock {
        public static final int MAX_LENGTH = 1000;

        public HypotheticalBlock {
            Objects.requireNonNull(content, "hypothetical content must not be null");
            content.require(
                    "hypothetical", 1, MAX_LENGTH, Integer.MAX_VALUE, RichText.TEXT_AND_TERM);
        }

        /** 글자 조각 하나로 된 가상의 계산 예(V1). */
        public HypotheticalBlock(String text) {
            this(RichText.text(text));
        }

        @Override
        public List<RichTextField> richTextFields() {
            return List.of(new RichTextField("content", content));
        }
    }

    /** '자주 하는 오해' 상자. {@code wrong}(틀린 생각) 1~300자, {@code correction}(바로잡기) 1~600자. */
    record MisconceptionBlock(RichText wrong, RichText correction) implements ArticleBlock {
        public static final int WRONG_MAX_LENGTH = 300;
        public static final int CORRECTION_MAX_LENGTH = 600;

        public MisconceptionBlock {
            Objects.requireNonNull(wrong, "misconception wrong must not be null");
            Objects.requireNonNull(correction, "misconception correction must not be null");
            wrong.require(
                    "misconception wrong",
                    1,
                    WRONG_MAX_LENGTH,
                    Integer.MAX_VALUE,
                    RichText.ALL_KINDS);
            correction.require(
                    "misconception correction",
                    1,
                    CORRECTION_MAX_LENGTH,
                    Integer.MAX_VALUE,
                    RichText.ALL_KINDS);
        }

        @Override
        public List<RichTextField> richTextFields() {
            return List.of(
                    new RichTextField("wrong", wrong), new RichTextField("correction", correction));
        }
    }

    /** 목록. 항목 1~8개, 항목마다 1~300자, 글자와 출처 표시만 담는다(FR-026). */
    record ListBlock(boolean ordered, List<RichText> items) implements ArticleBlock {
        public static final int MAX_ITEMS = 8;
        public static final int ITEM_MAX_LENGTH = 300;

        public ListBlock {
            items = List.copyOf(items);
            if (items.isEmpty() || items.size() > MAX_ITEMS) {
                throw new IllegalArgumentException(
                        "list must have 1.." + MAX_ITEMS + " items: " + items.size());
            }
            for (RichText item : items) {
                item.require(
                        "list item",
                        1,
                        ITEM_MAX_LENGTH,
                        Integer.MAX_VALUE,
                        RichText.TEXT_AND_SOURCE);
            }
        }

        @Override
        public List<RichTextField> richTextFields() {
            List<RichTextField> fields = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                fields.add(new RichTextField("items[" + index + "]", items.get(index)));
            }
            return fields;
        }
    }

    /**
     * 비교 표. 제목 0~100자, 열 2~5개(칸마다 1~30자), 행 1~8개이고 행마다 칸 수가 열 수와 같다. 칸은 0~100자이고 글자와 출처 표시만 담는다.
     */
    record TableBlock(String caption, List<String> columns, List<List<RichText>> rows)
            implements ArticleBlock {
        public static final int CAPTION_MAX_LENGTH = 100;
        public static final int MIN_COLUMNS = 2;
        public static final int MAX_COLUMNS = 5;
        public static final int COLUMN_MAX_LENGTH = 30;
        public static final int MAX_ROWS = 8;
        public static final int CELL_MAX_LENGTH = 100;

        public TableBlock {
            requireLength(caption, 0, CAPTION_MAX_LENGTH, "table caption");
            columns = List.copyOf(columns);
            if (columns.size() < MIN_COLUMNS || columns.size() > MAX_COLUMNS) {
                throw new IllegalArgumentException(
                        "table must have "
                                + MIN_COLUMNS
                                + ".."
                                + MAX_COLUMNS
                                + " columns: "
                                + columns.size());
            }
            for (String column : columns) {
                requireLength(column, 1, COLUMN_MAX_LENGTH, "table column");
            }
            List<List<RichText>> copied = new ArrayList<>();
            for (List<RichText> row : rows) {
                copied.add(List.copyOf(row));
            }
            rows = List.copyOf(copied);
            if (rows.isEmpty() || rows.size() > MAX_ROWS) {
                throw new IllegalArgumentException(
                        "table must have 1.." + MAX_ROWS + " rows: " + rows.size());
            }
            for (List<RichText> row : rows) {
                if (row.size() != columns.size()) {
                    throw new IllegalArgumentException(
                            "table row must have " + columns.size() + " cells: " + row.size());
                }
                for (RichText cell : row) {
                    cell.require(
                            "table cell",
                            0,
                            CELL_MAX_LENGTH,
                            Integer.MAX_VALUE,
                            RichText.TEXT_AND_SOURCE);
                }
            }
        }

        @Override
        public List<RichTextField> richTextFields() {
            List<RichTextField> fields = new ArrayList<>();
            for (int row = 0; row < rows.size(); row++) {
                for (int column = 0; column < rows.get(row).size(); column++) {
                    fields.add(
                            new RichTextField(
                                    "rows[" + row + "][" + column + "]",
                                    rows.get(row).get(column)));
                }
            }
            return fields;
        }
    }

    /** 도식 종류. */
    enum DiagramKind {
        BAR,
        LINE
    }

    /**
     * 가상 수치로 그린 막대·선 도식. 그리지 못하는 화면은 설명 문장만 보인다.
     *
     * @param title 0~60자
     * @param unit 0~10자
     * @param categories 2~12개, 칸마다 1~12자
     * @param series 1~3개. 계열마다 {@code values} 길이가 {@code categories} 길이와 같다
     * @param description 0~300자. 비었는지는 게시 전 검사({@code ELEMENT_FORMAT})가 본다
     */
    record DiagramBlock(
            DiagramKind kind,
            String title,
            String unit,
            List<String> categories,
            List<Series> series,
            String description)
            implements ArticleBlock {
        public static final int TITLE_MAX_LENGTH = 60;
        public static final int UNIT_MAX_LENGTH = 10;
        public static final int MIN_CATEGORIES = 2;
        public static final int MAX_CATEGORIES = 12;
        public static final int CATEGORY_MAX_LENGTH = 12;
        public static final int MAX_SERIES = 3;
        public static final int DESCRIPTION_MAX_LENGTH = 300;

        public DiagramBlock {
            Objects.requireNonNull(kind, "diagram kind must not be null");
            requireLength(title, 0, TITLE_MAX_LENGTH, "diagram title");
            requireLength(unit, 0, UNIT_MAX_LENGTH, "diagram unit");
            requireLength(description, 0, DESCRIPTION_MAX_LENGTH, "diagram description");
            categories = List.copyOf(categories);
            if (categories.size() < MIN_CATEGORIES || categories.size() > MAX_CATEGORIES) {
                throw new IllegalArgumentException(
                        "diagram must have "
                                + MIN_CATEGORIES
                                + ".."
                                + MAX_CATEGORIES
                                + " categories: "
                                + categories.size());
            }
            for (String category : categories) {
                requireLength(category, 1, CATEGORY_MAX_LENGTH, "diagram category");
            }
            series = List.copyOf(series);
            if (series.isEmpty() || series.size() > MAX_SERIES) {
                throw new IllegalArgumentException(
                        "diagram must have 1.." + MAX_SERIES + " series: " + series.size());
            }
            for (Series one : series) {
                if (one.values().size() != categories.size()) {
                    throw new IllegalArgumentException(
                            "diagram series must have "
                                    + categories.size()
                                    + " values: "
                                    + one.values().size());
                }
            }
        }

        /**
         * 계열 하나.
         *
         * @param name 1~20자
         * @param values 가상 수치. 절댓값 10⁹ 이하
         */
        public record Series(String name, List<BigDecimal> values) {
            public static final int NAME_MAX_LENGTH = 20;
            public static final BigDecimal MAX_ABS_VALUE = new BigDecimal("1000000000");

            public Series {
                requireLength(name, 1, NAME_MAX_LENGTH, "diagram series name");
                values = List.copyOf(values);
                for (BigDecimal value : values) {
                    if (value.abs().compareTo(MAX_ABS_VALUE) > 0) {
                        throw new IllegalArgumentException(
                                "diagram value must be within ±" + MAX_ABS_VALUE + ": " + value);
                    }
                }
            }
        }
    }

    /**
     * '제도 안내' 상자. 세법·거래소 규정처럼 바뀌는 사실을 기준 시점과 출처와 함께 적는다.
     *
     * @param content 1~600자
     * @param asOf 기준 시점. 초안에서는 비어 있어도 된다. 없거나 미래인지는 게시 전 검사({@code ELEMENT_FORMAT})가 본다
     * @param source 출처. 초안에서는 비어 있어도 된다
     */
    record InstitutionNoteBlock(RichText content, LocalDate asOf, SourceRef source)
            implements ArticleBlock {
        public static final int MAX_LENGTH = 600;

        public InstitutionNoteBlock {
            Objects.requireNonNull(content, "institution note content must not be null");
            content.require(
                    "institution note", 1, MAX_LENGTH, Integer.MAX_VALUE, RichText.ALL_KINDS);
        }

        @Override
        public List<RichTextField> richTextFields() {
            return List.of(new RichTextField("content", content));
        }
    }

    /** 조각 종류 집합. 블록 바깥(요청 검증)이 같은 규칙을 쓰도록 이름을 둔다. */
    static Set<RunKind> allowedRuns(Class<? extends ArticleBlock> type) {
        if (type == HypotheticalBlock.class) {
            return RichText.TEXT_AND_TERM;
        }
        if (type == ListBlock.class || type == TableBlock.class) {
            return RichText.TEXT_AND_SOURCE;
        }
        return RichText.ALL_KINDS;
    }

    private static void requireLength(String text, int min, int max, String name) {
        Objects.requireNonNull(text, name + " must not be null");
        if (text.length() < min || text.length() > max) {
            throw new IllegalArgumentException(
                    name + " must be " + min + ".." + max + " characters: " + text.length());
        }
    }
}
