package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.command.ReleaseStageCommand;
import com.ssafy.thorp.learning.application.command.ReleaseStageCommand.ExpectedArticle;
import com.ssafy.thorp.learning.application.exception.StageChangedException;
import com.ssafy.thorp.learning.application.exception.StageNotFoundException;
import com.ssafy.thorp.learning.application.exception.StageReleaseBlockedException;
import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort.SectorMapView;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.application.repository.LearningTermRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.repository.ReleaseLogRepository;
import com.ssafy.thorp.learning.application.result.PublishResult.TransferredTerm;
import com.ssafy.thorp.learning.application.result.StageAdminResult;
import com.ssafy.thorp.learning.application.result.StageAdminResult.StageArticle;
import com.ssafy.thorp.learning.application.result.StageReadinessResult;
import com.ssafy.thorp.learning.application.result.StageReadinessResult.ArticleReadiness;
import com.ssafy.thorp.learning.application.result.StageReleaseResult;
import com.ssafy.thorp.learning.application.result.StageReleaseResult.PublishedArticle;
import com.ssafy.thorp.learning.domain.exception.StageCheckFailedException;
import com.ssafy.thorp.learning.domain.exception.StageCheckFailedException.ArticleFailures;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * <p><b>공개는 한 트랜잭션이다.</b> 단계 행 잠금 → 이미 공개·앞 단계 공개 전·빈 단계 409 → 공개 집합의 글 행을 {@code id} 순서로 잠금 →
 * 목록·초안 번호 불일치 409 → 글마다 항목이 하나라도 걸리면 422로 아무것도 쓰지 않음 → 시각 하나로 글마다 게시·넘기기·첫 공개 시각·단계 공개일·변경 기록 한 줄.
 * 일부만 게시된 중간 상태가 생기지 않는다(SC-017).
 *
 * <p>단계 공개를 되돌리는 유스케이스는 없다(FR-031).
 */
@Service
@Transactional
public class LearningStageAdminService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final LearningArticleRepository articleRepository;
    private final LearningStageRepository stageRepository;
    private final LearningTermRepository termRepository;
    private final PublishedTermRepository publishedTermRepository;
    private final ReleaseLogRepository releaseLogRepository;
    private final SectorMapPort sectorMapPort;
    private final PublishCheckRulesPort rulesPort;
    private final TermOwnership termOwnership;
    private final ArticlePublisher publisher;
    private final Clock clock;

    public LearningStageAdminService(
            LearningArticleRepository articleRepository,
            LearningStageRepository stageRepository,
            LearningTermRepository termRepository,
            PublishedTermRepository publishedTermRepository,
            ReleaseLogRepository releaseLogRepository,
            SectorMapPort sectorMapPort,
            PublishCheckRulesPort rulesPort,
            TermOwnership termOwnership,
            ArticlePublisher publisher,
            Clock clock) {
        this.articleRepository = articleRepository;
        this.stageRepository = stageRepository;
        this.termRepository = termRepository;
        this.publishedTermRepository = publishedTermRepository;
        this.releaseLogRepository = releaseLogRepository;
        this.sectorMapPort = sectorMapPort;
        this.rulesPort = rulesPort;
        this.termOwnership = termOwnership;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 여섯 단계 전부, 번호 순서. 글은 한 번에 읽어 단계마다 나눈다. */
    @Transactional(readOnly = true)
    public List<StageAdminResult> list() {
        List<LearningStage> stages = stageRepository.findAllOrdered();
        List<LearningArticle> articles = articleRepository.findAllOrdered();
        Map<ArticleId, ArticleRevision> published = articleRepository.findPublishedRevisions();
        return stages.stream()
                .map(
                        stage ->
                                new StageAdminResult(
                                        stage,
                                        StageRelease.releasable(stage, stages),
                                        draftedIn(stage.stageKey(), articles).stream()
                                                .map(
                                                        article ->
                                                                view(
                                                                        article,
                                                                        article.placement()
                                                                                .position(),
                                                                        published))
                                                .toList(),
                                        placedOnlyIn(stage.stageKey(), articles).stream()
                                                .map(
                                                        article ->
                                                                view(
                                                                        article,
                                                                        article.publishedPlacement()
                                                                                .position(),
                                                                        published))
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
        List<LearningArticle> releaseSet = releaseSet(key, articleRepository.findAllOrdered());
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
     * @throws StageChangedException 요청의 글 목록이나 초안 번호가 잠근 뒤 다시 읽은 공개 집합과 다르다(409)
     * @throws StageCheckFailedException 글 하나라도 걸렸다(422). 아무것도 쓰지 않는다
     */
    public StageReleaseResult release(String stageKey, ReleaseStageCommand command) {
        StageKey key = parseStageKey(stageKey);
        LearningStage stage =
                stageRepository
                        .findByStageKeyForUpdate(key)
                        .orElseThrow(StageNotFoundException::new);
        List<LearningStage> stages = stageRepository.findAllOrdered();
        List<LearningArticle> unlocked = releaseSet(key, articleRepository.findAllOrdered());
        List<Blocker> blockers = StageRelease.blockers(stage, stages, unlocked.size());
        if (!blockers.isEmpty()) {
            throw new StageReleaseBlockedException(blockers.getFirst());
        }

        // 글 행을 id 순서로 잠그고 다시 읽는다. 그사이 저장·이동이 커밋됐으면 잠근 값이 기준이다.
        Map<ArticleId, LearningArticle> locked = new HashMap<>();
        unlocked.stream()
                .sorted(Comparator.comparing(LearningArticle::id))
                .forEach(
                        article ->
                                articleRepository
                                        .findByArticleKeyForUpdate(article.articleId())
                                        .ifPresent(fresh -> locked.put(fresh.articleId(), fresh)));
        List<LearningArticle> releaseSet =
                releaseSet(
                        key,
                        unlocked.stream()
                                .map(article -> locked.get(article.articleId()))
                                .filter(article -> article != null)
                                .toList());
        if (!matches(command.articles(), releaseSet)) {
            throw new StageChangedException(releaseSet);
        }

        PublishCheckRulesV2 rules = rulesPort.v2();
        // 검사와 판에 남길 대응표 버전이 같은 대응표여야 한다. 한 번만 읽는다.
        Optional<SectorMapView> map = sectorMapFor(releaseSet);
        Map<ArticleId, List<PublishCheckFailure>> failures = judge(key, releaseSet, rules, map);
        List<ArticleFailures> failed =
                releaseSet.stream()
                        .filter(article -> !failures.get(article.articleId()).isEmpty())
                        .map(
                                article ->
                                        new ArticleFailures(
                                                article.articleId(),
                                                failures.get(article.articleId())))
                        .toList();
        if (!failed.isEmpty()) {
            throw new StageCheckFailedException(rules.version(), failed);
        }

        Instant releasedAt = now();
        Map<ArticleId, ArticlePublisher.Outcome> outcomes = new HashMap<>();
        for (LearningArticle article : publishOrder(releaseSet)) {
            outcomes.put(
                    article.articleId(),
                    publisher.publish(
                            article,
                            article.draftVersion(),
                            PublishMode.CURRENT,
                            rules.version(),
                            article.example() == null
                                    ? ArticleRevision.NOT_CHECKED
                                    : requireMapVersion(map),
                            rules.reading(),
                            command.actorUserId(),
                            releasedAt));
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
     * 공개 집합의 글마다 걸리는 항목: 형식·초안 배치, 용어 소유, v2 검사 순서다. V1 초안은 v2 검사를 받을 수 없어 {@code FORMAT}과 용어 소유
     * 항목만 싣는다.
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
        LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);
        Map<ArticleId, List<PublishCheckFailure>> failures = new HashMap<>();
        for (LearningArticle article : releaseSet) {
            List<PublishCheckFailure> found =
                    new ArrayList<>(StageRelease.articleFailures(article, stageKey));
            termOwnership.violations(article.articleId(), article.terms()).stream()
                    .map(TermOwnership.Violation::toFailure)
                    .forEach(found::add);
            if (article.format() == ArticleFormat.V2) {
                if (known == null) {
                    known = knownTermIds();
                }
                found.addAll(
                        PublishCheckV2.run(article, rules, openSectorCodes, known, today)
                                .failures());
            }
            failures.put(article.articleId(), found);
        }
        return failures;
    }

    /**
     * 공개 집합: 초안 단계가 이 단계인 글(초안 단계 안 순서), 그다음 공개 배치만 이 단계인 글(공개 배치 단계 안 순서)이다.
     *
     * @param articles 초안 단계 번호, 단계 안 순서, {@code articleId} 순서의 글
     */
    private static List<LearningArticle> releaseSet(
            StageKey stageKey, List<LearningArticle> articles) {
        List<LearningArticle> set = new ArrayList<>(draftedIn(stageKey, articles));
        set.addAll(placedOnlyIn(stageKey, articles));
        return set;
    }

    private static List<LearningArticle> draftedIn(
            StageKey stageKey, List<LearningArticle> articles) {
        return articles.stream()
                .filter(article -> article.placement().stageKey().equals(stageKey))
                .sorted(
                        Comparator.comparingInt(
                                        (LearningArticle article) -> article.placement().position())
                                .thenComparing(article -> article.articleId().value()))
                .toList();
    }

    private static List<LearningArticle> placedOnlyIn(
            StageKey stageKey, List<LearningArticle> articles) {
        return articles.stream()
                .filter(article -> !article.placement().stageKey().equals(stageKey))
                .filter(article -> StageRelease.inReleaseSet(article, stageKey))
                .sorted(
                        Comparator.comparingInt(
                                        (LearningArticle article) ->
                                                article.publishedPlacement().position())
                                .thenComparing(article -> article.articleId().value()))
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

    /**
     * 게시 순서. 같은 공개 안에서 다른 글이 용어를 넘겨받는 옛 소유 글은 넘겨받는 글보다 먼저 게시한다. 넘겨받는 글을 먼저 게시하면 넘기기가 옛 글의 초안 행을 지우고
     * 초안 번호를 올린 뒤라, 잠가 둔 옛 글의 값으로 게시하면 그 변경을 덮어쓰고 그 용어의 색인 행이 겹친다. 그 밖의 순서는 단계 안 순서다.
     *
     * <p>넘겨받기가 고리를 이루면(서로의 용어를 넘겨받음) 지킬 순서가 없다. 그 글들은 단계 안 순서로 두고, 색인 행이 겹쳐 트랜잭션 전체가 되돌려진다.
     */
    private static List<LearningArticle> publishOrder(List<LearningArticle> releaseSet) {
        List<LearningArticle> pending = new ArrayList<>(releaseSet);
        List<LearningArticle> ordered = new ArrayList<>();
        while (!pending.isEmpty()) {
            Set<ArticleId> waiting = new HashSet<>();
            pending.forEach(article -> waiting.add(article.articleId()));
            // 넘겨줄 옛 글이 아직 게시를 기다리는 글은 다음 차례로 미룬다.
            List<LearningArticle> ready =
                    pending.stream()
                            .filter(
                                    article ->
                                            article.terms().stream()
                                                    .filter(LearningTerm::transferring)
                                                    .noneMatch(
                                                            term ->
                                                                    waiting.contains(
                                                                            term.transferFrom())))
                            .toList();
            if (ready.isEmpty()) {
                ordered.addAll(pending);
                break;
            }
            ordered.addAll(ready);
            pending.removeAll(ready);
        }
        return ordered;
    }

    private StageArticle view(
            LearningArticle article, int stagePosition, Map<ArticleId, ArticleRevision> published) {
        return new StageArticle(
                article.articleId().value(),
                article.title(),
                article.format(),
                article.status(),
                Optional.ofNullable(published.get(article.articleId()))
                        .map(ArticleRevision::edition)
                        .orElse(null),
                article.draftVersion(),
                stagePosition);
    }

    /** 언급 조각이 가리킬 수 있는 용어: 모든 글의 초안 용어와 게시 용어 색인의 합집합(data-model §8-2 #13). */
    private Set<TermId> knownTermIds() {
        Set<TermId> known = new HashSet<>(termRepository.findAllTermIds());
        known.addAll(publishedTermRepository.findAllTermIds());
        return known;
    }

    /** 예시가 있는 글이 하나라도 있을 때만 대응표를 읽는다. 예시 없는 V2 글은 대응표를 대조하지 않는다(원칙 VI). */
    private Optional<SectorMapView> sectorMapFor(List<LearningArticle> releaseSet) {
        return releaseSet.stream().anyMatch(article -> article.example() != null)
                ? sectorMapPort.currentMap()
                : Optional.empty();
    }

    /** 검사를 통과한 예시가 있는 글은 대응표가 있었다. 그 버전을 판에 남긴다. */
    private static String requireMapVersion(Optional<SectorMapView> map) {
        return map.map(SectorMapView::mapVersion)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "publish check passed without a sector map"));
    }

    /** DB가 마이크로초까지 저장하므로 응답 값과 저장 값이 같도록 자른다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static StageKey parseStageKey(String raw) {
        try {
            return new StageKey(raw);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new StageNotFoundException();
        }
    }
}
