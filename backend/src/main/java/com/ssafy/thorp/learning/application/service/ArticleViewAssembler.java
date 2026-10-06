package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.port.SectorMapPort.SectorMapView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Block;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.ChoiceView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.DiagramView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Example;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.ExampleSector;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.HypotheticalView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.InstitutionNoteView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.KeySummaryView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.LegacyTermBlock;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.ListView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.MentionRunView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.MisconceptionView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.ParagraphView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.QuizView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.RunView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.SeriesView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Source;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.SourceRunView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.TableView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.TermView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.TextBlock;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.TextRunView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.UnavailableReason;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
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
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.QuizChoice;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.model.RichText;
import com.ssafy.thorp.learning.domain.model.RichText.Run;
import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 글 내용(판 또는 초안)을 공개 글 응답의 블록·확인 문제·예시로 옮긴다(learning-public-api §3, learning-admin-api §8). 공개 조회와
 * 관리 미리 보기가 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * <p><b>공개 판은 엄격하게, 초안은 있는 그대로</b>({@link Mode}). 공개 판에서 용어 블록이 판에 없는 용어를 가리키거나 V1 판에 예시가 없으면 저장
 * 데이터가 깨진 것이라 실패시킨다. 빈 문장으로 채우면 없는 설명을 만들어 내게 된다(원칙 VI). 미리 보기는 아직 검사를 받지 않은 초안을 보이므로 그 자리를 비워 둔다.
 */
final class ArticleViewAssembler {

    /** 공개 판인가, 미리 보는 초안인가. */
    enum Mode {
        PUBLISHED,
        PREVIEW
    }

    private ArticleViewAssembler() {}

    /** 이 글이 소유하지 않은 언급 용어. 게시 용어 색인에서 다른 이름을 읽을 대상이다(data-model §5-4). */
    static Set<TermId> foreignMentions(List<ArticleBlock> blocks, List<LearningTerm> terms) {
        Set<TermId> own = new LinkedHashSet<>();
        terms.forEach(term -> own.add(term.termId()));
        Set<TermId> foreign = new LinkedHashSet<>();
        for (ArticleBlock block : blocks) {
            for (ArticleBlock.RichTextField field : block.richTextFields()) {
                for (TermMentionRun mention : field.text().mentions()) {
                    if (!own.contains(mention.termId())) {
                        foreign.add(mention.termId());
                    }
                }
            }
        }
        return foreign;
    }

    /**
     * 블록을 응답 모양으로 옮긴다. V1은 9월 판 모양 그대로, V2는 글자 조각에 {@code owned}·{@code aliases}를 채운다.
     *
     * @param foreignAliases 다른 글이 소유한 용어의 다른 이름(게시 용어 색인). 게시되지 않은 용어는 없다
     */
    static List<Block> blocks(
            ArticleId articleId,
            ArticleFormat format,
            List<ArticleBlock> blocks,
            List<LearningTerm> terms,
            Map<TermId, List<String>> foreignAliases,
            Mode mode) {
        Map<TermId, LearningTerm> own = new HashMap<>();
        terms.forEach(term -> own.put(term.termId(), term));
        return blocks.stream()
                .map(block -> block(articleId, format, block, own, foreignAliases, mode))
                .toList();
    }

    private static Block block(
            ArticleId articleId,
            ArticleFormat format,
            ArticleBlock block,
            Map<TermId, LearningTerm> own,
            Map<TermId, List<String>> foreignAliases,
            Mode mode) {
        boolean v1 = format == ArticleFormat.V1;
        return switch (block) {
            case HeadingBlock heading -> new TextBlock("heading", heading.text());
            case ParagraphBlock paragraph ->
                    v1
                            ? new TextBlock("paragraph", paragraph.content().plainText())
                            : new ParagraphView(runs(paragraph.content(), own, foreignAliases));
            case HypotheticalBlock hypothetical ->
                    v1
                            ? new TextBlock("hypothetical", hypothetical.content().plainText())
                            : new HypotheticalView(
                                    runs(hypothetical.content(), own, foreignAliases));
            case TermBlock termBlock -> {
                LearningTerm term = own.get(termBlock.termId());
                if (term == null) {
                    if (mode == Mode.PUBLISHED) {
                        throw new IllegalStateException(
                                "published revision of "
                                        + articleId
                                        + " has a term block without its term: "
                                        + termBlock.termId());
                    }
                    // 미리 보기: 아직 이 글에 없는 용어다. 게시 전 검사(TERM_BLOCKS_MATCH)가 막는 자리를 비워 보인다.
                    term = new LearningTerm(termBlock.termId(), "", "");
                }
                yield v1
                        ? new LegacyTermBlock(term.termId().value(), term.label(), term.sentence())
                        : new TermView(
                                term.termId().value(),
                                term.label(),
                                term.aliases(),
                                term.meaning(),
                                term.reading(),
                                term.caveat());
            }
            case KeySummaryBlock summary ->
                    new KeySummaryView(
                            summary.lines().stream()
                                    .map(line -> runs(line, own, foreignAliases))
                                    .toList());
            case MisconceptionBlock misconception ->
                    new MisconceptionView(
                            runs(misconception.wrong(), own, foreignAliases),
                            runs(misconception.correction(), own, foreignAliases));
            case ListBlock list ->
                    new ListView(
                            list.ordered(),
                            list.items().stream()
                                    .map(item -> runs(item, own, foreignAliases))
                                    .toList());
            case TableBlock table ->
                    new TableView(
                            table.caption(),
                            table.columns(),
                            table.rows().stream()
                                    .map(
                                            row ->
                                                    row.stream()
                                                            .map(
                                                                    cell ->
                                                                            runs(
                                                                                    cell,
                                                                                    own,
                                                                                    foreignAliases))
                                                            .toList())
                                    .toList());
            case DiagramBlock diagram ->
                    new DiagramView(
                            diagram.kind().name().toLowerCase(Locale.ROOT),
                            diagram.title(),
                            diagram.unit(),
                            diagram.categories(),
                            diagram.series().stream()
                                    .map(series -> new SeriesView(series.name(), series.values()))
                                    .toList(),
                            diagram.description());
            case InstitutionNoteBlock note ->
                    new InstitutionNoteView(
                            runs(note.content(), own, foreignAliases),
                            note.asOf(),
                            note.source() == null
                                    ? null
                                    : new Source(note.source().label(), note.source().url()));
        };
    }

    private static List<RunView> runs(
            RichText text,
            Map<TermId, LearningTerm> own,
            Map<TermId, List<String>> foreignAliases) {
        return text.runs().stream().map(run -> run(run, own, foreignAliases)).toList();
    }

    private static RunView run(
            Run run, Map<TermId, LearningTerm> own, Map<TermId, List<String>> foreignAliases) {
        return switch (run) {
            case TextRun textRun -> new TextRunView(textRun.text());
            case TermMentionRun mention -> {
                LearningTerm owned = own.get(mention.termId());
                yield new MentionRunView(
                        mention.termId().value(),
                        mention.text(),
                        owned != null,
                        owned != null
                                ? owned.aliases()
                                : foreignAliases.getOrDefault(mention.termId(), List.of()));
            }
            case SourceRun source ->
                    new SourceRunView(new Source(source.source().label(), source.source().url()));
        };
    }

    /**
     * 확인 문제. 공개 판은 정답 보기가 정확히 하나다. 미리 보기에서 정답이 하나가 아니면 {@code correctChoiceId}를 비운다. 게시 전 검사({@code
     * QUIZ})가 막는 자리다.
     */
    static List<QuizView> quiz(List<QuizQuestion> quiz, Mode mode) {
        return quiz.stream()
                .map(
                        question -> {
                            List<QuizChoice> correct =
                                    question.choices().stream()
                                            .filter(QuizChoice::correct)
                                            .toList();
                            if (correct.size() != 1 && mode == Mode.PUBLISHED) {
                                throw new IllegalStateException(
                                        "published question must have exactly one correct choice: "
                                                + question.questionId());
                            }
                            return new QuizView(
                                    question.questionId().value(),
                                    question.prompt(),
                                    question.choices().stream()
                                            .map(
                                                    choice ->
                                                            new ChoiceView(
                                                                    choice.choiceId().value(),
                                                                    choice.text()))
                                            .toList(),
                                    correct.size() == 1
                                            ? correct.getFirst().choiceId().value()
                                            : null,
                                    question.explanation());
                        })
                .toList();
    }

    /**
     * 조회 시점의 열린 대응표로 예시를 판정한다(research R8). 판정 결과를 저장하지 않고 판의 예시 값도 바꾸지 않는다.
     *
     * <p>예시가 없으면 대응표를 읽지 않는다. V2 글은 예시가 선택이라 null로 돌려준다. V1 공개 판에 예시가 없으면 게시 전 검사({@code
     * EXAMPLE_PRESENT})가 막는 상태라 실패시킨다.
     *
     * @param sectorMap 대응표를 읽는다. 예시가 있을 때 한 번만 부른다
     */
    static Example example(
            ArticleId articleId,
            ArticleFormat format,
            ExampleAllocation example,
            Supplier<Optional<SectorMapView>> sectorMap,
            Mode mode) {
        if (example == null) {
            if (format == ArticleFormat.V1 && mode == Mode.PUBLISHED) {
                throw new IllegalStateException(
                        "published revision of " + articleId + " has no example");
            }
            return null;
        }
        Optional<SectorMapView> map = sectorMap.get();
        if (map.isEmpty()) {
            return new Example(
                    false,
                    example.sectors().stream()
                            .map(
                                    sector ->
                                            new ExampleSector(
                                                    sector.code(), null, sector.weightTenths()))
                            .toList(),
                    List.of(),
                    UnavailableReason.SECTOR_MAP_NOT_READY);
        }
        Map<String, String> names = map.get().sectorNamesByCode();
        List<ExampleSector> sectors =
                example.sectors().stream()
                        .map(
                                sector ->
                                        new ExampleSector(
                                                sector.code(),
                                                names.get(sector.code()),
                                                sector.weightTenths()))
                        .toList();
        List<String> missing =
                example.sectors().stream()
                        .map(ExampleAllocation.SectorWeight::code)
                        .filter(code -> !names.containsKey(code))
                        .toList();
        return missing.isEmpty()
                ? new Example(true, sectors, List.of(), null)
                : new Example(false, sectors, missing, UnavailableReason.SECTOR_NOT_IN_MAP);
    }
}
