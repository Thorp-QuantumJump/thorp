package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure.Check;
import com.ssafy.thorp.learning.domain.model.PublishCheckRules.ForbiddenGroup;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import com.ssafy.thorp.learning.domain.vo.TermId;
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
 * 게시 전 검사(research R7). 초안·규칙·지금 열린 대응표의 섹터 코드를 받아 결과를 돌려주는 순수 함수다. 대응표를 조회하지 않고 아무것도 바꾸지 않는다.
 *
 * <p><b>아홉 검사를 모두 실행하고 걸린 항목을 전부 돌려준다.</b> 처음 걸린 항목에서 멈추면 운영자가 고치고 다시 누르기를 되풀이한다.
 *
 * <p><b>한계</b>: 금지 표현과 결과성 숫자는 패턴 검사다. 목록에 없는 우회 표현이나 문맥상 권고로 읽히는 문장은 잡지 못한다. 이 검사를 통과했다는 사실을
 * SC-002 달성의 근거로 삼지 않는다.
 */
public final class PublishCheck {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private PublishCheck() {}

    /**
     * @param openSectorCodes 지금 열린 대응표의 섹터 코드. 대응표를 읽지 못했으면 비어 있고, 그때는 {@code
     *     EXAMPLE_SECTOR_IN_MAP}으로 거절한다. 판정할 수 없는 것을 통과로 세지 않는다(원칙 VI)
     */
    public static PublishCheckResult run(
            LearningArticle draft, PublishCheckRules rules, Optional<Set<String>> openSectorCodes) {
        List<PublishCheckFailure> failures = new ArrayList<>();
        requiredFields(draft, failures);
        example(draft.example(), openSectorCodes, failures);
        termBlocks(draft, failures);
        termSentences(draft, failures);
        forbiddenExpressions(texts(draft, true), rules, failures);
        resultNumbers(texts(draft, false), rules, failures);
        return new PublishCheckResult(failures);
    }

    /** 공백 문자 연속을 공백 하나로 줄인다. 금지 표현은 이렇게 줄인 문자열에서 찾는다. */
    public static String collapseWhitespace(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ");
    }

    private static void requiredFields(LearningArticle draft, List<PublishCheckFailure> failures) {
        if (draft.title().isBlank()) {
            failures.add(fail(Check.REQUIRED_FIELDS, CheckLocation.title(), "제목이 비어 있습니다."));
        }
        if (draft.question().isBlank()) {
            failures.add(fail(Check.REQUIRED_FIELDS, CheckLocation.question(), "질문이 비어 있습니다."));
        }
        if (draft.blocks().stream().noneMatch(ParagraphBlock.class::isInstance)) {
            failures.add(fail(Check.REQUIRED_FIELDS, CheckLocation.body(), "본문에 문단이 하나도 없습니다."));
        }
    }

    /** 예시가 없으면 나머지 예시 검사는 판정할 대상이 없다. {@code EXAMPLE_PRESENT}가 이미 게시를 막는다. */
    private static void example(
            ExampleAllocation example,
            Optional<Set<String>> openSectorCodes,
            List<PublishCheckFailure> failures) {
        if (example == null) {
            failures.add(fail(Check.EXAMPLE_PRESENT, CheckLocation.example(), "예시 섹터와 비중이 없습니다."));
            return;
        }
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

    /** 이 글의 용어가 본문에 {@code term} 블록으로 정확히 한 번씩 나오고, {@code term} 블록이 이 글의 용어만 가리키는지. */
    private static void termBlocks(LearningArticle draft, List<PublishCheckFailure> failures) {
        Set<TermId> ownTerms = new LinkedHashSet<>();
        draft.terms().forEach(term -> ownTerms.add(term.termId()));
        Map<TermId, Integer> occurrences = new LinkedHashMap<>();
        List<ArticleBlock> blocks = draft.blocks();
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

    private static void forbiddenExpressions(
            List<LocatedText> texts, PublishCheckRules rules, List<PublishCheckFailure> failures) {
        Set<Reported> reported = new LinkedHashSet<>();
        for (LocatedText text : texts) {
            String normalized = collapseWhitespace(text.text());
            for (ForbiddenGroup group : rules.forbiddenGroups()) {
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

    private static void resultNumbers(
            List<LocatedText> texts, PublishCheckRules rules, List<PublishCheckFailure> failures) {
        Set<Reported> reported = new LinkedHashSet<>();
        for (LocatedText text : texts) {
            String normalized = collapseWhitespace(text.text());
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
            for (Match match : matches) {
                if (reported.add(new Reported(text.location(), match.text()))) {
                    failures.add(
                            new PublishCheckFailure(
                                    Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL,
                                    text.location(),
                                    match.text(),
                                    "실제 결과로 읽히는 숫자는 가상의 계산 예 블록에만 쓸 수 있습니다."));
                }
            }
        }
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
                        texts.add(new LocatedText(location, paragraph.text()));
                case HypotheticalBlock hypothetical -> {
                    if (includeHypothetical) {
                        texts.add(new LocatedText(location, hypothetical.text()));
                    }
                }
                case TermBlock ignored -> {
                    // 문장은 용어 설명에 있다. 아래에서 용어 위치로 본다.
                }
            }
        }
        for (LearningTerm term : draft.terms()) {
            CheckLocation location = CheckLocation.term(term.termId());
            texts.add(new LocatedText(location, term.label()));
            texts.add(new LocatedText(location, term.sentence()));
        }
        return texts;
    }

    private static PublishCheckFailure fail(Check check, CheckLocation location, String message) {
        return new PublishCheckFailure(check, location, null, message);
    }

    private record LocatedText(CheckLocation location, String text) {}

    private record Match(int start, String text) {}

    /** 같은 위치에서 같은 문자열은 한 번만 보고한다. 용어는 이름과 문장이 한 위치다. */
    private record Reported(CheckLocation location, String matched) {}
}
