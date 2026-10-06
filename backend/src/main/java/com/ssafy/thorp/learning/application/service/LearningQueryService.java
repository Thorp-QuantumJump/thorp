package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.exception.ArticleUnavailableException;
import com.ssafy.thorp.learning.application.exception.TermNotReadyException;
import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.result.CurrentArticleEntry;
import com.ssafy.thorp.learning.application.result.LearningArticleResult;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.NextArticle;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Source;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.StageRef;
import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.application.result.PublishedArticleView;
import com.ssafy.thorp.learning.application.service.ArticleViewAssembler.Mode;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.model.ReadingEstimate;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배우기 공개 조회 유스케이스(US1·US3 서버, learning-public-api).
 *
 * <p><b>공개 중인 판, 공개 상태(단계의 공개 여부, 글의 공개 배치)와 게시 용어 색인만 읽는다.</b> 초안 작업본({@code learning_article}의 본문
 * 컬럼, {@code learning_term})으로 응답을 만들지 않는다. 게시 뒤 초안을 고쳐도 공개 내용이 바뀌지 않아야 한다(SC-006).
 *
 * <p>사용자를 식별하지 않고 조회를 사용자별로 남기지 않는다(FR-012).
 */
@Service
@Transactional(readOnly = true)
public class LearningQueryService {

    private final LearningArticleRepository articleRepository;
    private final PublishedTermRepository publishedTermRepository;
    private final LearningStageRepository stageRepository;
    private final SectorMapPort sectorMapPort;
    private final PublishCheckRulesPort rulesPort;

    public LearningQueryService(
            LearningArticleRepository articleRepository,
            PublishedTermRepository publishedTermRepository,
            LearningStageRepository stageRepository,
            SectorMapPort sectorMapPort,
            PublishCheckRulesPort rulesPort) {
        this.articleRepository = articleRepository;
        this.publishedTermRepository = publishedTermRepository;
        this.stageRepository = stageRepository;
        this.sectorMapPort = sectorMapPort;
        this.rulesPort = rulesPort;
    }

    /**
     * 공개 중인 글(LEGACY·CURRENT 모두). 판의 {@code readingOrder}, {@code articleId} 오름차순이다. 9월 판 목록 경로가 쓴다
     * (learning-public-api §2). 017의 글 열거도 이 조회를 쓴다.
     *
     * @return 게시된 글이 없으면 빈 목록. 빈 목록은 정상 화면이다(spec Edge Cases)
     */
    public List<LearningArticleSummaryResult> listPublished() {
        return articleRepository.findPublishedOrdered();
    }

    /**
     * 공개 중인 글 하나(learning-public-api §3).
     *
     * <p>{@code edition}은 판의 종류와 단계 공개 상태로 정한다. {@code CURRENT} 판은 {@code CURRENT}, {@code LEGACY}
     * 판은 공개된 단계가 하나도 없으면 {@code PRE_RELEASE}(지금 모습 그대로), 있으면 {@code PREVIOUS}(이전 판)다(R16). {@code
     * LEGACY} 판의 블록은 9월 판 모양 그대로다.
     *
     * @throws ArticleUnavailableException 없는 글, 게시되지 않은 글, 형식이 틀린 식별자. 셋을 가르지 않는다
     */
    public LearningArticleResult getPublished(String articleId) {
        PublishedArticleView view =
                parseArticleId(articleId)
                        .flatMap(articleRepository::findPublished)
                        .orElseThrow(ArticleUnavailableException::new);
        ArticleRevision revision = view.revision();
        ArticleSnapshot snapshot = revision.snapshot();
        // 공개 판 포인터가 다른 글의 판을 가리키면 그 글의 본문을 이 주소로 내보내게 된다.
        // 스키마가 막지 않는 관계라 여기서 확인하고, 어긋나면 조용히 보이지 않고 실패시킨다.
        if (!snapshot.articleId().value().equals(articleId)) {
            throw new IllegalStateException(
                    "published revision of "
                            + articleId
                            + " belongs to another article: "
                            + snapshot.articleId());
        }
        List<LearningStage> stages = stageRepository.findAllOrdered();
        LearningStage stage = stage(stages, view);
        boolean current = revision.edition() == Edition.CURRENT;

        String number = null;
        NextArticle next = null;
        Integer stageArticleCount = null;
        if (current) {
            List<CurrentArticleEntry> order = articleRepository.findCurrentInReadingOrder();
            int position = indexOf(order, articleId);
            CurrentArticleEntry self = order.get(position);
            number = number(order, self);
            if (position + 1 < order.size()) {
                CurrentArticleEntry following = order.get(position + 1);
                next =
                        new NextArticle(
                                following.articleId(), number(order, following), following.title());
            }
            stageArticleCount =
                    (int)
                            order.stream()
                                    .filter(entry -> entry.stageKey().equals(self.stageKey()))
                                    .count();
        }

        Set<TermId> foreign =
                current
                        ? ArticleViewAssembler.foreignMentions(
                                snapshot.bodyBlocks(), snapshot.terms())
                        : Set.of();
        Map<TermId, List<String>> foreignAliases =
                foreign.isEmpty()
                        ? Map.of()
                        : publishedTermRepository.findPublishedAliases(foreign);
        return new LearningArticleResult(
                snapshot.articleId().value(),
                current
                        ? "CURRENT"
                        : stages.stream().anyMatch(LearningStage::released)
                                ? "PREVIOUS"
                                : "PRE_RELEASE",
                snapshot.title(),
                snapshot.question(),
                snapshot.readingOrder(),
                new StageRef(stage.stageKey().value(), stage.number(), stage.name()),
                number,
                current
                        ? snapshot.reading().readingMinutes()
                        : ReadingEstimate.minutes(
                                ReadingEstimate.bodyChars(snapshot.bodyBlocks(), snapshot.terms()),
                                rulesPort.v2().reading().charsPerMinute()),
                current ? snapshot.reading().minDwellSeconds() : null,
                ArticleViewAssembler.blocks(
                        snapshot.articleId(),
                        snapshot.format(),
                        snapshot.bodyBlocks(),
                        snapshot.terms(),
                        foreignAliases,
                        Mode.PUBLISHED),
                ArticleViewAssembler.quiz(snapshot.quiz(), Mode.PUBLISHED),
                ArticleViewAssembler.example(
                        snapshot.articleId(),
                        snapshot.format(),
                        snapshot.example(),
                        sectorMapPort::currentMap,
                        Mode.PUBLISHED),
                snapshot.sources().stream()
                        .map(source -> new Source(source.label(), source.url()))
                        .toList(),
                next,
                stageArticleCount,
                stages.stream().anyMatch(candidate -> !candidate.released()),
                revision.publishedAt(),
                false);
    }

    /**
     * 공개 중인 용어 설명 하나. 게시 용어 색인만 읽는다.
     *
     * @throws TermNotReadyException 게시된 설명이 없거나 형식이 틀린 식별자. 둘을 가르지 않는다
     */
    public LearningTermResult getPublishedTerm(String termId) {
        return parseTermId(termId)
                .flatMap(publishedTermRepository::findPublished)
                .orElseThrow(TermNotReadyException::new);
    }

    /**
     * 글 번호 {@code {단계 번호}-{그 단계의 공개된 글 가운데 읽는 순서 순번}}(data-model §12-2). {@code order}는 읽는 순서라 같은
     * 단계 글이 이어져 있다.
     */
    private static String number(List<CurrentArticleEntry> order, CurrentArticleEntry entry) {
        int ordinal = 0;
        for (CurrentArticleEntry candidate : order) {
            if (candidate.stageKey().equals(entry.stageKey())) {
                ordinal++;
            }
            if (candidate.articleId().equals(entry.articleId())) {
                return entry.stageNumber() + "-" + ordinal;
            }
        }
        throw new IllegalStateException("article is not in the reading order: " + entry);
    }

    /** CURRENT 판이 게시 중인 글이면 읽는 순서에 있다. 없으면 글 행과 판이 어긋난 것이라 실패시킨다. */
    private static int indexOf(List<CurrentArticleEntry> order, String articleId) {
        for (int index = 0; index < order.size(); index++) {
            if (order.get(index).articleId().equals(articleId)) {
                return index;
            }
        }
        throw new IllegalStateException(
                "current article is missing from the reading order: " + articleId);
    }

    private static LearningStage stage(List<LearningStage> stages, PublishedArticleView view) {
        return stages.stream()
                .filter(stage -> stage.stageKey().equals(view.placement().stageKey()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "published placement points to a missing stage: "
                                                + view.placement()));
    }

    private static Optional<ArticleId> parseArticleId(String raw) {
        try {
            return Optional.of(new ArticleId(raw));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<TermId> parseTermId(String raw) {
        try {
            return Optional.of(new TermId(raw));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }
}
