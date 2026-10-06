package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.InstitutionNoteBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.KeySummaryBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ListBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.MisconceptionBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TableBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure.Check;
import com.ssafy.thorp.learning.domain.model.PublishCheckRules.ForbiddenGroup;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 게시 전 검사 v1(research R7, data-model §8-1). 초안·규칙·지금 열린 대응표의 섹터 코드를 받아 결과를 돌려주는 순수 함수다. 대응표를 조회하지
 * 않고 아무것도 바꾸지 않는다.
 *
 * <p><b>아홉 검사를 모두 실행하고 걸린 항목을 전부 돌려준다.</b> 처음 걸린 항목에서 멈추면 운영자가 고치고 다시 누르기를 되풀이한다.
 *
 * <p><b>V1 초안(이전 판 고치기)만 받는다.</b> V1 초안의 문단과 가상의 계산 예는 글자 조각 하나이므로 그 글자를 9월 판의 {@code text}로 보고 판정과
 * 결과를 1판과 같게 둔다. V2 초안이나 V2에만 있는 블록이 오면 호출하는 쪽의 오류로 보고 예외를 던진다. 건너뛰어 통과로 세지 않는다(원칙 VI). V2 초안은
 * {@link PublishCheckV2}가 본다. 두 검사가 같은 규칙을 쓰는 부분(예시 모양, 용어 블록 짝, 금지 표현, 결과성 숫자)은 이 클래스의 패키지 메서드를 함께
 * 쓴다.
 *
 * <p><b>한계</b>: 금지 표현과 결과성 숫자는 패턴 검사다. 목록에 없는 우회 표현이나 문맥상 권고로 읽히는 문장은 잡지 못한다. 이 검사를 통과했다는 사실을
 * SC-002 달성의 근거로 삼지 않는다.
 */
public final class PublishCheck {

    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private PublishCheck() {}

    /**
     * @param openSectorCodes 지금 열린 대응표의 섹터 코드. 대응표를 읽지 못했으면 비어 있고, 그때는 {@code
     *     EXAMPLE_SECTOR_IN_MAP}으로 거절한다. 판정할 수 없는 것을 통과로 세지 않는다(원칙 VI)
     * @throws IllegalStateException V1 초안이 아니다
     */
    public static PublishCheckResult run(
            LearningArticle draft, PublishCheckRules rules, Optional<Set<String>> openSectorCodes) {
        if (draft.format() != ArticleFormat.V1) {
            throw new IllegalStateException(
                    "v1 publish check received a "
                            + draft.format()
                            + " draft: "
                            + draft.articleId());
        }
        List<PublishCheckFailure> failures = new ArrayList<>();
        requiredFields(draft, failures);
        example(draft.example(), openSectorCodes, failures);
        termBlocks(draft.blocks(), draft.terms(), failures);
        termSentences(draft, failures);
        forbiddenExpressions(texts(draft, true), rules.forbiddenGroups(), failures);
        resultNumbers(
                texts(draft, false), rules, "실제 결과로 읽히는 숫자는 가상의 계산 예 블록에만 쓸 수 있습니다.", failures);
        return new PublishCheckResult(failures);
    }

    /**
     * 호환 문자를 표준 문자로 바꾸고(NFKC) 공백 문자 연속을 공백 하나로 줄인다. 금지 표현과 결과성 숫자는 이렇게 바꾼 문자열에서 찾는다.
     *
     * <p>문서에서 붙여 넣은 줄바꿈 없는 공백(U+00A0)·전각 공백과 전각 숫자·{@code ％}가 검사를 비켜 가지 않게 하려는 것이다. 걸린 문자열({@code
     * matched})도 바꾼 뒤의 값이다.
     */
    public static String collapseWhitespace(String text) {
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll(" ");
    }

    private static void requiredFields(LearningArticle draft, List<PublishCheckFailure> failures) {
        if (draft.title().isBlank()) {
            failures.add(fail(Check.REQUIRED_FIELDS, CheckLocation.title(), "제목이 비어 있습니다."));
        }
        if (draft.question().isBlank()) {
            failures.add(fail(Check.REQUIRED_FIELDS, CheckLocation.question(), "질문이 비어 있습니다."));
        }
        if (draft.blocks().stream()
                .noneMatch(
                        block ->
                                block instanceof ParagraphBlock paragraph
                                        && !paragraph.content().plainText().isBlank())) {
            failures.add(fail(Check.REQUIRED_FIELDS, CheckLocation.body(), "본문에 문단이 하나도 없습니다."));
        }
    }

    /** 예시가 없으면 나머지 예시 검사는 판정할 대상이 없다. {@code EXAMPLE_PRESENT}가 이미 게시를 막는다. */
    private static void example(
            ExampleAllocation example,
            Optional<Set<String>> openSectorCodes,
            List<PublishCheckFailure> failures) {
        if (example == null) {
            failures.add(examplePresent());
            return;
        }
        exampleShape(example, openSectorCodes, failures);
    }

    static PublishCheckFailure examplePresent() {
        return fail(Check.EXAMPLE_PRESENT, CheckLocation.example(), "예시 섹터와 비중이 없습니다.");
    }

    /**
     * 있는 예시의 섹터 수·대응표·비중 합계({@code EXAMPLE_SECTOR_COUNT}·{@code EXAMPLE_SECTOR_IN_MAP}·{@code
     * EXAMPLE_WEIGHT_SUM}). v2도 같은 규칙이다.
     */
    static void exampleShape(
            ExampleAllocation example,
            Optional<Set<String>> openSectorCodes,
            List<PublishCheckFailure> failures) {
        int count = example.sectors().size();
        if (count < 2) {
            failures.add(
                    fail(
                            Check.EXAMPLE_SECTOR_COUNT,
                            CheckLocation.example(),
                            "예시 섹터는 두 개 이상이어야 합니다. 지금은 " + count + "개입니다."));
        }
        if (openSectorCodes.isEmpty()) {
            failures.add(
                    fail(
                            Check.EXAMPLE_SECTOR_IN_MAP,
                            CheckLocation.example(),
                            "대응표를 확인할 수 없어 예시 섹터가 지금 열린 대응표에 있는지 판정하지 못했습니다."));
        } else {
            List<String> missing =
                    example.sectors().stream()
                            .map(SectorWeight::code)
                            .filter(code -> !openSectorCodes.get().contains(code))
                            .toList();
            if (!missing.isEmpty()) {
                failures.add(
                        fail(
                                Check.EXAMPLE_SECTOR_IN_MAP,
                                CheckLocation.example(),
                                "지금 열린 대응표에 없는 섹터가 있습니다: " + String.join(", ", missing) + "."));
            }
        }
        int total = example.totalWeightTenths();
        if (total != ExampleAllocation.FULL_WEIGHT_TENTHS) {
            failures.add(
                    fail(
                            Check.EXAMPLE_WEIGHT_SUM,
                            CheckLocation.example(),
                            "예시 비중의 합계가 100%가 아닙니다. 지금 합계는 "
                                    + (total / 10)
                                    + "."
                                    + (total % 10)
                                    + "%입니다."));
        }
    }

    /**
     * 이 글의 용어가 본문에 {@code term} 블록으로 정확히 한 번씩 나오고, {@code term} 블록이 이 글의 용어만 가리키는지. v2도 같은 규칙이다.
     */
    static void termBlocks(
            List<ArticleBlock> blocks,
            List<LearningTerm> terms,
            List<PublishCheckFailure> failures) {
        Set<TermId> ownTerms = new LinkedHashSet<>();
        terms.forEach(term -> ownTerms.add(term.termId()));
        Map<TermId, Integer> occurrences = new LinkedHashMap<>();
        for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index) instanceof TermBlock termBlock) {
                occurrences.merge(termBlock.termId(), 1, Integer::sum);
                if (!ownTerms.contains(termBlock.termId())) {
                    failures.add(
                            fail(
                                    Check.TERM_BLOCKS_MATCH,
                                    CheckLocation.block(index),
                                    "이 글에 없는 용어를 가리키는 용어 블록입니다: " + termBlock.termId() + "."));
                }
            }
        }
        for (TermId termId : ownTerms) {
            int count = occurrences.getOrDefault(termId, 0);
            if (count == 0) {
                failures.add(
                        fail(
                                Check.TERM_BLOCKS_MATCH,
                                CheckLocation.term(termId),
                                "용어 설명이 본문에 용어 블록으로 들어 있지 않습니다."));
            } else if (count > 1) {
                failures.add(
                        fail(
                                Check.TERM_BLOCKS_MATCH,
                                CheckLocation.term(termId),
                                "용어 설명이 본문에 용어 블록으로 " + count + "번 들어 있습니다. 한 번만 넣어 주세요."));
            }
        }
    }

    private static void termSentences(LearningArticle draft, List<PublishCheckFailure> failures) {
        for (LearningTerm term : draft.terms()) {
            CheckLocation location = CheckLocation.term(term.termId());
            if (term.label().isBlank()) {
                failures.add(fail(Check.TERM_SENTENCE, location, "용어 이름이 비어 있습니다."));
            }
            if (term.sentence().isBlank()) {
                failures.add(fail(Check.TERM_SENTENCE, location, "용어 설명 문장이 비어 있습니다."));
            } else if (term.sentence().length() > LearningTerm.SENTENCE_MAX_LENGTH) {
                failures.add(
                        fail(
                                Check.TERM_SENTENCE,
                                location,
                                "용어 설명 문장이 "
                                        + LearningTerm.SENTENCE_MAX_LENGTH
                                        + "자를 넘습니다. 지금 "
                                        + term.sentence().length()
                                        + "자입니다."));
            }
        }
    }

    /** 금지 표현을 찾는다. 같은 위치의 같은 표현은 한 번만 보고한다. v2도 같은 규칙이다. */
    static void forbiddenExpressions(
            List<LocatedText> texts,
            List<ForbiddenGroup> groups,
            List<PublishCheckFailure> failures) {
        Set<Reported> reported = new LinkedHashSet<>();
        for (LocatedText text : texts) {
            String normalized = collapseWhitespace(text.text());
            for (ForbiddenGroup group : groups) {
                for (String expression : group.expressions()) {
                    if (normalized.contains(expression)
                            && reported.add(new Reported(text.location(), expression))) {
                        failures.add(
                                new PublishCheckFailure(
                                        Check.FORBIDDEN_EXPRESSION,
                                        text.location(),
                                        expression,
                                        group.message()));
                    }
                }
            }
        }
    }

    /**
     * 결과성 숫자를 찾는다. 같은 위치의 같은 숫자는 한 번만 보고한다. 숫자가 그 텍스트의 허용 구간({@link LocatedText#exempt})에서 시작하면 걸지
     * 않는다.
     *
     * @param message 걸린 항목의 문장. v1과 v2가 허용 자리를 다르게 안내한다
     */
    static void resultNumbers(
            List<LocatedText> texts,
            PublishCheckRules rules,
            String message,
            List<PublishCheckFailure> failures) {
        Set<Reported> reported = new LinkedHashSet<>();
        for (LocatedText text : texts) {
            String normalized = collapseWhitespace(text.text());
            for (Match match : resultNumberMatches(normalized, rules)) {
                if (text.exempt(normalized, match.start())) {
                    continue;
                }
                if (reported.add(new Reported(text.location(), match.text()))) {
                    failures.add(
                            new PublishCheckFailure(
                                    Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL,
                                    text.location(),
                                    match.text(),
                                    message));
                }
            }
        }
    }

    /** 바꾼 문자열({@link #collapseWhitespace})에서 걸리는 숫자. 시작 위치 순서다. 허용 예외와 정확히 같은 문자열은 뺀다. */
    private static List<Match> resultNumberMatches(String normalized, PublishCheckRules rules) {
        List<Match> matches = new ArrayList<>();
        for (Pattern pattern : rules.resultNumberPatterns()) {
            Matcher matcher = pattern.matcher(normalized);
            while (matcher.find()) {
                if (!rules.allowedExceptions().contains(matcher.group())) {
                    matches.add(new Match(matcher.start(), matcher.group()));
                }
            }
        }
        matches.sort(Comparator.comparingInt(Match::start));
        return matches;
    }

    /**
     * 검사할 텍스트를 위치 순서(제목·질문·블록·용어)로 늘어놓는다. 용어는 이름과 문장을 모두 본다.
     *
     * @param includeHypothetical 금지 표현은 {@code hypothetical} 블록 안도 보고, 결과성 숫자는 보지 않는다
     */
    private static List<LocatedText> texts(LearningArticle draft, boolean includeHypothetical) {
        List<LocatedText> texts = new ArrayList<>();
        texts.add(new LocatedText(CheckLocation.title(), draft.title()));
        texts.add(new LocatedText(CheckLocation.question(), draft.question()));
        List<ArticleBlock> blocks = draft.blocks();
        for (int index = 0; index < blocks.size(); index++) {
            CheckLocation location = CheckLocation.block(index);
            switch (blocks.get(index)) {
                case HeadingBlock heading -> texts.add(new LocatedText(location, heading.text()));
                case ParagraphBlock paragraph ->
                        texts.add(new LocatedText(location, paragraph.content().plainText()));
                case HypotheticalBlock hypothetical -> {
                    if (includeHypothetical) {
                        texts.add(new LocatedText(location, hypothetical.content().plainText()));
                    }
                }
                case TermBlock ignored -> {
                    // 문장은 용어 설명에 있다. 아래에서 용어 위치로 본다.
                }
                case KeySummaryBlock block -> throw v2Only(draft, block);
                case MisconceptionBlock block -> throw v2Only(draft, block);
                case ListBlock block -> throw v2Only(draft, block);
                case TableBlock block -> throw v2Only(draft, block);
                case DiagramBlock block -> throw v2Only(draft, block);
                case InstitutionNoteBlock block -> throw v2Only(draft, block);
            }
        }
        for (LearningTerm term : draft.terms()) {
            CheckLocation location = CheckLocation.term(term.termId());
            texts.add(new LocatedText(location, term.label()));
            texts.add(new LocatedText(location, term.sentence()));
        }
        return texts;
    }

    /** V1 초안은 V2 블록을 담지 못한다({@link LearningArticle}). 여기 왔다면 저장 데이터가 깨졌거나 호출하는 쪽이 형식을 잘못 골랐다. */
    private static IllegalStateException v2Only(LearningArticle draft, ArticleBlock block) {
        return new IllegalStateException(
                "v1 publish check received a V2 block "
                        + block.getClass().getSimpleName()
                        + ": "
                        + draft.articleId());
    }

    static PublishCheckFailure fail(Check check, CheckLocation location, String message) {
        return new PublishCheckFailure(check, location, null, message);
    }

    /**
     * 검사할 텍스트 하나.
     *
     * @param exemptMarker 이 표지로 시작해 다음 {@code )}로 닫히는 괄호 안에서 시작하는 숫자는 걸지 않는다(v2의 {@code (예:}).
     *     null이면 허용 구간이 없다(v1)
     */
    record LocatedText(CheckLocation location, String text, String exemptMarker) {

        LocatedText(CheckLocation location, String text) {
            this(location, text, null);
        }

        /**
         * 바꾼 문자열의 {@code start} 위치가 허용 구간 안인가. 구간은 표지의 첫 글자부터 그 뒤 첫 {@code )}까지다. 닫는 괄호가 없으면 구간이
         * 아니다. 끝을 모르는 괄호까지 허용하면 문단 나머지 전체가 검사에서 빠진다.
         */
        boolean exempt(String normalized, int start) {
            if (exemptMarker == null) {
                return false;
            }
            int from = normalized.indexOf(exemptMarker);
            while (from >= 0) {
                int to = normalized.indexOf(')', from + exemptMarker.length());
                if (to < 0) {
                    return false;
                }
                if (start >= from && start < to) {
                    return true;
                }
                from = normalized.indexOf(exemptMarker, to + 1);
            }
            return false;
        }
    }

    private record Match(int start, String text) {}

    /** 같은 위치에서 같은 문자열은 한 번만 보고한다. v1 용어는 이름과 문장이 한 위치다. */
    private record Reported(CheckLocation location, String matched) {}
}
