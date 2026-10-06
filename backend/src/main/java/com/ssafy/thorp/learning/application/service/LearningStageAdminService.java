package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.command.ReleaseStageCommand;
import com.ssafy.thorp.learning.application.command.ReleaseStageCommand.ExpectedArticle;
import com.ssafy.thorp.learning.application.exception.StageChangedException;
import com.ssafy.thorp.learning.application.exception.StageNotFoundException;
import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort.SectorMapView;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.application.repository.ReleaseLogRepository;
import com.ssafy.thorp.learning.application.result.PublishResult.TransferredTerm;
import com.ssafy.thorp.learning.application.result.StageAdminResult;
import com.ssafy.thorp.learning.application.result.StageAdminResult.StageArticle;
import com.ssafy.thorp.learning.application.result.StageArticleEntry;
import com.ssafy.thorp.learning.application.result.StageReadinessResult;
import com.ssafy.thorp.learning.application.result.StageReadinessResult.ArticleReadiness;
import com.ssafy.thorp.learning.application.result.StageReleaseResult;
import com.ssafy.thorp.learning.application.result.StageReleaseResult.PublishedArticle;
import com.ssafy.thorp.learning.domain.exception.StageCheckFailedException;
import com.ssafy.thorp.learning.domain.exception.StageCheckFailedException.ArticleFailures;
import com.ssafy.thorp.learning.domain.exception.StageReleaseBlockedException;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2;
import com.ssafy.thorp.learning.domain.model.PublishCheckV2;
import com.ssafy.thorp.learning.domain.model.PublishMode;
import com.ssafy.thorp.learning.domain.model.ReleaseLogEntry;
import com.ssafy.thorp.learning.domain.model.StageRelease;
import com.ssafy.thorp.learning.domain.model.StageRelease.Blocker;
import com.ssafy.thorp.learning.domain.model.StageRelease.Tally;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단계 목록·단계 미리 검사·단계 공개(US5 서버, learning-admin-api §9~§11, data-model §10-3, research R23). 권한 판정은
 * 호출 전에 끝나 있다({@code LearningAdminAccess}).
 *
 * <p><b>미리 검사와 공개는 같은 판정을 쓴다</b>({@link StageRelease}, {@link TermOwnership}, {@link
 * PublishCheckV2}). 미리 검사는 아무것도 쓰지 않는다. 공개는 되돌리지 않으므로, 확인만 하려던 운영자가 공개해 버리는 일을 막으려고 둘을 나눈다.
 *
 * <p><b>공개는 한 트랜잭션이다.</b> 단계 행 잠금 → 이미 공개·앞 단계 공개 전·빈 단계 409 → 공개 집합과 그 글들이 넘겨받는 옛 소유 글을 {@code
 * articleId} 순서로 잠금({@link ArticlePublisher#lockWithGivers}) → 공개 집합을 다시 읽어 바뀌었거나 목록·초안 번호가 다르면 409
 * → 글마다 항목이 하나라도 걸리면 422로 아무것도 쓰지 않음 → 시각 하나로 글마다 게시·넘기기·첫 공개 시각·단계 공개일·변경 기록 한 줄. 일부만 게시된 중간 상태가
 * 생기지 않는다(SC-017). 글마다 게시도 단계 행 → 글 순서로 잠그므로 둘이 서로 기다리지 않고, 공개가 도는 동안 그 단계에 글을 따로 게시하지 못한다.
 *
 * <p>목록과 공개 집합은 본문을 읽지 않는 값({@link StageArticleEntry})으로 정하고, 검사할 공개 집합의 글만 전부 읽는다.
 *
 * <p>단계 공개를 되돌리는 유스케이스는 없다(FR-031).
 */
@Service
@Transactional
public class LearningStageAdminService {

    private final LearningArticleRepository articleRepository;
    private final LearningStageRepository stageRepository;
    private final ReleaseLogRepository releaseLogRepository;
    private final SectorMapPort sectorMapPort;
    private final PublishCheckRulesPort rulesPort;
    private final TermOwnership termOwnership;
    private final ArticlePublisher publisher;
    private final Clock clock;

    public LearningStageAdminService(
            LearningArticleRepository articleRepository,
            LearningStageRepository stageRepository,
            ReleaseLogRepository releaseLogRepository,
            SectorMapPort sectorMapPort,
            PublishCheckRulesPort rulesPort,
            TermOwnership termOwnership,
            ArticlePublisher publisher,
            Clock clock) {
        this.articleRepository = articleRepository;
        this.stageRepository = stageRepository;
        this.releaseLogRepository = releaseLogRepository;
        this.sectorMapPort = sectorMapPort;
        this.rulesPort = rulesPort;
        this.termOwnership = termOwnership;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 여섯 단계 전부, 번호 순서. 글은 본문 없이 한 번에 읽어 단계마다 나눈다. */
    @Transactional(readOnly = true)
    public List<StageAdminResult> list() {
        List<LearningStage> stages = stageRepository.findAllOrdered();
        List<StageArticleEntry> entries = articleRepository.findStageEntries();
        return stages.stream()
                .map(
                        stage ->
                                new StageAdminResult(
                                        stage,
                                        StageRelease.releasable(stage, stages),
                                        draftedIn(stage.stageKey(), entries).stream()
                                                .map(
                                                        entry ->
                                                                view(
                                                                        entry,
                                                                        entry.placement()
                                                                                .position()))
                                                .toList(),
                                        placedOnlyIn(stage.stageKey(), entries).stream()
                                                .map(
                                                        entry ->
                                                                view(
                                                                        entry,
                                                                        entry.publishedPlacement()
                                                                                .position()))
                                                .toList()))
                .toList();
    }

    /**
     * 단계 공개와 같은 판정을 하되 아무것도 쓰지 않는다(learning-admin-api §10).
     *
     * @throws StageNotFoundException 없는 단계, 형식이 틀린 식별자
     */
    @Transactional(readOnly = true)
    public StageReadinessResult readiness(String stageKey) {
        StageKey key = parseStageKey(stageKey);
        LearningStage stage =
                stageRepository.findByStageKey(key).orElseThrow(StageNotFoundException::new);
        List<LearningStage> stages = stageRepository.findAllOrdered();
        List<LearningArticle> releaseSet =
                load(releaseSetIds(key, articleRepository.findStageEntries()));
        PublishCheckRulesV2 rules = rulesPort.v2();
        Map<ArticleId, List<PublishCheckFailure>> failures =
                judge(key, releaseSet, rules, sectorMapFor(releaseSet));
        return new StageReadinessResult(
                key.value(),
                StageRelease.blockers(stage, stages, releaseSet.size()),
                releaseSet.stream()
                        .map(
                                article ->
                                        new ArticleReadiness(
                                                article.articleId().value(),
                                                article.draftVersion(),
                                                failures.get(article.articleId())))
                        .toList(),
                rules.version());
    }

    /**
     * 단계를 공개한다(learning-admin-api §11, data-model §10-3). 공개일은 이 요청의 시각 하나다.
     *
     * @throws StageNotFoundException 없는 단계
     * @throws StageReleaseBlockedException 이미 공개, 앞 단계 공개 전, 빈 단계(409). 아무것도 쓰지 않는다
     * @throws StageChangedException 요청의 글 목록이나 초안 번호가 공개 집합과 다르다(409). 잠그기 전에 한 번, 잠근 뒤에 한 번 본다
     * @throws StageCheckFailedException 글 하나라도 걸렸다(422). 아무것도 쓰지 않는다
     */
    public StageReleaseResult release(String stageKey, ReleaseStageCommand command) {
        StageKey key = parseStageKey(stageKey);
        LearningStage stage =
                stageRepository
                        .findByStageKeyForUpdate(key)
                        .orElseThrow(StageNotFoundException::new);
        List<LearningStage> stages = stageRepository.findAllOrdered();
        List<ArticleId> setIds = releaseSetIds(key, articleRepository.findStageEntries());
        List<Blocker> blockers = StageRelease.blockers(stage, stages, setIds.size());
        if (!blockers.isEmpty()) {
            throw new StageReleaseBlockedException(blockers.getFirst());
        }

        // 잠그기 전에 요청과 맞춰 본다. 잠근 뒤에도 초안 번호가 같으면 초안이 그대로라, 잠그기 전에 읽은 넘겨받는 용어로 정한 옛 소유 글도 맞다.
        List<LearningArticle> unlocked = load(setIds);
        if (!matches(command.articles(), unlocked)) {
            throw new StageChangedException(unlocked);
        }
        Map<ArticleId, LearningArticle> locked = publisher.lockWithGivers(unlocked);
        // 잠그는 사이 저장(단계 이동)이나 게시 취소가 커밋됐으면 공개 집합이 바뀐다. 잠근 값으로 다시 정한다.
        List<ArticleId> lockedSetIds = releaseSetIds(key, articleRepository.findStageEntries());
        // 글이 모두 다른 단계로 옮겨 갔으면 빈 단계다. 빈 요청과 맞아떨어져 글 없이 공개되지 않게 다시 본다.
        if (lockedSetIds.isEmpty()) {
            throw new StageReleaseBlockedException(Blocker.EMPTY);
        }
        if (!new HashSet<>(lockedSetIds).equals(new HashSet<>(setIds))) {
            throw new StageChangedException(load(lockedSetIds));
        }
        List<LearningArticle> releaseSet = lockedSetIds.stream().map(locked::get).toList();
        if (!matches(command.articles(), releaseSet)) {
            throw new StageChangedException(releaseSet);
        }

        PublishCheckRulesV2 rules = rulesPort.v2();
        // 검사와 판에 남길 대응표 버전이 같은 대응표여야 한다. 한 번만 읽는다.
        Optional<SectorMapView> map = sectorMapFor(releaseSet);
        List<ArticleFailures> failed = failed(releaseSet, judge(key, releaseSet, rules, map));
        if (!failed.isEmpty()) {
            throw new StageCheckFailedException(rules.version(), failed);
        }

        Instant releasedAt = LearningTime.now(clock);
        Map<ArticleId, ArticlePublisher.Outcome> outcomes = new HashMap<>();
        Set<ArticleId> bumpedGivers = new HashSet<>();
        for (LearningArticle candidate : StageRelease.publishOrder(releaseSet)) {
            // 앞서 게시한 글의 넘기기가 이 글의 초안 번호를 올렸을 수 있다(넘겨준 글). 잠근 행을 다시 읽어 그 값으로 게시한다.
            LearningArticle article =
                    articleRepository
                            .findByArticleKeyForUpdate(candidate.articleId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "locked article vanished: "
                                                            + candidate.articleId()));
            outcomes.put(
                    article.articleId(),
                    publisher.publish(
                            article,
                            article.draftVersion(),
                            PublishMode.CURRENT,
                            rules.version(),
                            ArticlePublisher.mapVersion(article, map),
                            rules.reading(),
                            command.actorUserId(),
                            releasedAt,
                            bumpedGivers));
        }
        stageRepository.saveRelease(stage.release(releasedAt, command.actorUserId()));

        Tally tally = Tally.empty();
        List<PublishedArticle> published = new ArrayList<>();
        List<TransferredTerm> transferred = new ArrayList<>();
        for (LearningArticle article : releaseSet) {
            ArticlePublisher.Outcome outcome = outcomes.get(article.articleId());
            tally =
                    tally.add(
                            outcome.firstRelease(),
                            outcome.hadLegacyRevision(),
                            outcome.article().title());
            published.add(
                    new PublishedArticle(
                            article.articleId().value(), outcome.revisionNo(), outcome.renamed()));
            transferred.addAll(outcome.transferred());
        }
        ReleaseLogEntry entry =
                ReleaseLogEntry.stageReleased(
                        key,
                        tally.newCount(),
                        tally.rewrittenTitles(),
                        command.actorUserId(),
                        releasedAt);
        releaseLogRepository.append(entry);
        return new StageReleaseResult(
                key.value(), releasedAt, published, transferred, entry.entryKey(), rules.version());
    }

    /**
     * 단계 공개가 용어 UNIQUE 제약에 걸린 뒤, 지금 상태로 공개 집합을 다시 판정해 422로 바꾼다. 먼저 커밋한 다른 저장·게시가 같은 용어를 가져간 경우다.
     * 컨트롤러가 실패한 트랜잭션이 끝난 뒤 부른다(글마다 게시의 {@code requireTermIdsAvailable}와 같은 방식). 걸린 글이 없으면 아무것도 하지
     * 않는다.
     *
     * @throws StageCheckFailedException 걸린 글이 있다(422)
     */
    @Transactional(readOnly = true)
    public void requireChecksPass(String stageKey) {
        StageKey key = parseStageKey(stageKey);
        List<LearningArticle> releaseSet =
                load(releaseSetIds(key, articleRepository.findStageEntries()));
        PublishCheckRulesV2 rules = rulesPort.v2();
        List<ArticleFailures> failed =
                failed(releaseSet, judge(key, releaseSet, rules, sectorMapFor(releaseSet)));
        if (!failed.isEmpty()) {
            throw new StageCheckFailedException(rules.version(), failed);
        }
    }

    /**
     * 공개 집합의 글마다 걸리는 항목: 형식·초안 배치, 용어 소유(초안 용어 순서), v2 검사 순서다. V1 초안은 v2 검사를 받을 수 없어 앞의 두 갈래만 싣는다.
     * 용어 소유에는 같은 공개에서 넘겨받을 용어를 옛 소유 글이 아직 초안에 둔 경우({@link StageRelease#termsKeptByGivers})가 든다.
     *
     * @param map 예시 검사에 쓸 대응표. 예시가 있는 글이 없으면 읽지 않아 비어 있다
     */
    private Map<ArticleId, List<PublishCheckFailure>> judge(
            StageKey stageKey,
            List<LearningArticle> releaseSet,
            PublishCheckRulesV2 rules,
            Optional<SectorMapView> map) {
        Set<TermId> known = null;
        Optional<Set<String>> openSectorCodes = map.map(view -> view.sectorNamesByCode().keySet());
        LocalDate today = LearningTime.today(clock);
        Map<ArticleId, Map<TermId, ArticleId>> kept = StageRelease.termsKeptByGivers(releaseSet);
        Map<ArticleId, List<PublishCheckFailure>> failures = new HashMap<>();
        for (LearningArticle article : releaseSet) {
            List<PublishCheckFailure> found =
                    new ArrayList<>(StageRelease.articleFailures(article, stageKey));
            Map<TermId, PublishCheckFailure> ownership = new HashMap<>();
            termOwnership
                    .violations(article.articleId(), article.terms())
                    .forEach(violation -> ownership.put(violation.termId(), violation.toFailure()));
            Map<TermId, ArticleId> given = kept.getOrDefault(article.articleId(), Map.of());
            for (LearningTerm term : article.terms()) {
                Optional.ofNullable(ownership.get(term.termId())).ifPresent(found::add);
                Optional.ofNullable(given.get(term.termId()))
                        .map(receiver -> StageRelease.keptTermFailure(term.termId(), receiver))
                        .ifPresent(found::add);
            }
            if (article.format() == ArticleFormat.V2) {
                if (known == null) {
                    known = termOwnership.knownTermIds();
                }
                found.addAll(
                        PublishCheckV2.run(article, rules, openSectorCodes, known, today)
                                .failures());
            }
            failures.put(article.articleId(), found);
        }
        return failures;
    }

    /** 걸린 글만, 공개 집합 순서. */
    private static List<ArticleFailures> failed(
            List<LearningArticle> releaseSet, Map<ArticleId, List<PublishCheckFailure>> failures) {
        return releaseSet.stream()
                .filter(article -> !failures.get(article.articleId()).isEmpty())
                .map(
                        article ->
                                new ArticleFailures(
                                        article.articleId(), failures.get(article.articleId())))
                .toList();
    }

    /** 이 글들을 전부 읽는다. 순서는 넘긴 순서다. */
    private List<LearningArticle> load(List<ArticleId> articleIds) {
        Map<ArticleId, LearningArticle> byId =
                articleRepository.findByArticleKeys(articleIds).stream()
                        .collect(Collectors.toMap(LearningArticle::articleId, article -> article));
        return articleIds.stream()
                .map(
                        id -> {
                            LearningArticle article = byId.get(id);
                            if (article == null) {
                                throw new IllegalStateException("learning article vanished: " + id);
                            }
                            return article;
                        })
                .toList();
    }

    /**
     * 공개 집합: 초안 단계가 이 단계인 글(초안 단계 안 순서), 그다음 공개 배치만 이 단계인 글(공개 배치 단계 안 순서)이다.
     *
     * @param entries 초안 단계 번호, 단계 안 순서, {@code articleId} 순서의 글
     */
    private static List<ArticleId> releaseSetIds(
            StageKey stageKey, List<StageArticleEntry> entries) {
        List<ArticleId> set = new ArrayList<>();
        draftedIn(stageKey, entries).forEach(entry -> set.add(entry.articleId()));
        placedOnlyIn(stageKey, entries).forEach(entry -> set.add(entry.articleId()));
        return set;
    }

    private static List<StageArticleEntry> draftedIn(
            StageKey stageKey, List<StageArticleEntry> entries) {
        return entries.stream()
                .filter(entry -> entry.placement().stageKey().equals(stageKey))
                .sorted(
                        Comparator.comparingInt(
                                        (StageArticleEntry entry) -> entry.placement().position())
                                .thenComparing(entry -> entry.articleId().value()))
                .toList();
    }

    private static List<StageArticleEntry> placedOnlyIn(
            StageKey stageKey, List<StageArticleEntry> entries) {
        return entries.stream()
                .filter(entry -> !entry.placement().stageKey().equals(stageKey))
                .filter(
                        entry ->
                                StageRelease.inReleaseSet(
                                        entry.placement(), entry.publishedPlacement(), stageKey))
                .sorted(
                        Comparator.comparingInt(
                                        (StageArticleEntry entry) ->
                                                entry.publishedPlacement().position())
                                .thenComparing(entry -> entry.articleId().value()))
                .toList();
    }

    /** 요청의 글 집합이 공개 집합과 같고 초안 번호가 모두 맞는가. 순서는 보지 않는다. 같은 글을 두 번 실으면 다르다. */
    private static boolean matches(
            List<ExpectedArticle> expected, List<LearningArticle> releaseSet) {
        if (expected.size() != releaseSet.size()) {
            return false;
        }
        Map<String, Integer> versions =
                releaseSet.stream()
                        .collect(
                                Collectors.toMap(
                                        article -> article.articleId().value(),
                                        LearningArticle::draftVersion));
        Set<String> seen = new HashSet<>();
        for (ExpectedArticle article : expected) {
            Integer version = versions.get(article.articleId());
            if (version == null
                    || version != article.expectedDraftVersion()
                    || !seen.add(article.articleId())) {
                return false;
            }
        }
        return true;
    }

    private static StageArticle view(StageArticleEntry entry, int stagePosition) {
        return new StageArticle(
                entry.articleId().value(),
                entry.title(),
                entry.format(),
                entry.status(),
                entry.publishedEdition(),
                entry.draftVersion(),
                stagePosition);
    }

    /** 예시가 있는 글이 하나라도 있을 때만 대응표를 읽는다. 예시 없는 V2 글은 대응표를 대조하지 않는다(원칙 VI). */
    private Optional<SectorMapView> sectorMapFor(List<LearningArticle> releaseSet) {
        return releaseSet.stream().anyMatch(article -> article.example() != null)
                ? sectorMapPort.currentMap()
                : Optional.empty();
    }

    private static StageKey parseStageKey(String raw) {
        try {
            return new StageKey(raw);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new StageNotFoundException();
        }
    }
}
