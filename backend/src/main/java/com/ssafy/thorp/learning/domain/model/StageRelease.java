package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.PublishCheckFailure.Check;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 단계 공개의 전제 판정(data-model §10-2·§10-3, research R23). 저장소를 읽지 않는 순수 함수다. 단계 공개와 그 미리 검사가 같은 판정을 쓴다.
 *
 * <p>판정은 둘로 나뉜다. 공개 자체를 막는 것({@link Blocker})과 공개 집합의 글마다 걸리는 것(형식·초안 배치. 용어 소유와 v2 검사는 호출자가
 * 더한다)이다.
 */
public final class StageRelease {

    private StageRelease() {}

    /** 글 검사와 별개로 공개를 막는 것(learning-admin-api §10 {@code blockers}). 순서는 판정 순서다. */
    public enum Blocker {
        /** 이미 공개했다. 공개는 되돌리지 않는다(FR-031). */
        ALREADY_RELEASED,
        /** 앞 번호 단계가 공개 전이다(US5 시나리오 7). */
        ORDER,
        /** 공개 집합이 비었다. */
        EMPTY
    }

    /**
     * 공개 자체를 막는 것. 1단계는 앞 단계 조건이 없다.
     *
     * @param stages 여섯 단계 전부
     * @param releaseSetSize 공개 집합의 글 수
     */
    public static List<Blocker> blockers(
            LearningStage stage, List<LearningStage> stages, int releaseSetSize) {
        List<Blocker> blockers = new ArrayList<>();
        if (stage.released()) {
            blockers.add(Blocker.ALREADY_RELEASED);
        }
        if (!previousReleased(stage, stages)) {
            blockers.add(Blocker.ORDER);
        }
        if (releaseSetSize == 0) {
            blockers.add(Blocker.EMPTY);
        }
        return blockers;
    }

    /** 공개 전이고 앞 단계가 공개되었다(learning-admin-api §9 {@code releasable}). 검사 통과 여부는 보지 않는다. */
    public static boolean releasable(LearningStage stage, List<LearningStage> stages) {
        return !stage.released() && previousReleased(stage, stages);
    }

    /** 공개 집합에 드는 글인가. 초안 배치가 이 단계인 글과 공개 배치가 이 단계인 글이다. 공개 배치만 이 단계인 글은 {@code PLACEMENT}로 걸린다. */
    public static boolean inReleaseSet(LearningArticle article, StageKey stageKey) {
        return inReleaseSet(article.placement(), article.publishedPlacement(), stageKey);
    }

    /**
     * {@link #inReleaseSet(LearningArticle, StageKey)}를 배치만으로 판정한다. 글 전체를 읽지 않고 공개 집합을 정할 때 쓴다.
     *
     * @param publishedPlacement 게시되지 않았으면 null
     */
    public static boolean inReleaseSet(
            Placement placement, Placement publishedPlacement, StageKey stageKey) {
        return placement.stageKey().equals(stageKey)
                || (publishedPlacement != null && publishedPlacement.stageKey().equals(stageKey));
    }

    /**
     * 공개 집합의 글 하나가 형식과 초안 배치로 걸리는 항목. V1 초안이면 {@code FORMAT}, 초안을 다른 단계로 옮겼으면 {@code PLACEMENT}다.
     */
    public static List<PublishCheckFailure> articleFailures(
            LearningArticle article, StageKey stageKey) {
        List<PublishCheckFailure> failures = new ArrayList<>();
        if (article.format() != ArticleFormat.V2) {
            failures.add(
                    new PublishCheckFailure(
                            Check.FORMAT,
                            CheckLocation.article(),
                            null,
                            "이전 형식(V1) 초안입니다. 새 기준(V2)으로 다시 써야 단계와 함께 공개됩니다."));
        }
        if (!article.placement().stageKey().equals(stageKey)) {
            failures.add(
                    new PublishCheckFailure(
                            Check.PLACEMENT,
                            CheckLocation.article(),
                            null,
                            "초안을 다른 단계로 옮긴 글입니다. 옮긴 단계로 다시 게시하거나 게시 취소한 뒤 공개합니다."));
        }
        return failures;
    }

    /**
     * 같은 공개에서 넘겨받을 용어를 옛 소유 글이 아직 초안에 둔 경우(옛 소유 글 → 용어 → 넘겨받는 글). 옛 소유 글도 이 공개에 들 때만 본다.
     *
     * <p>그대로 공개하면 옛 글의 새 판이 넘겨준 용어를 소유한 채 공개되어, 그 용어의 도움말이 가리키는 글과 어긋난다(FR-008). 두 글이 서로의 용어를 넘겨받는
     * 고리도 이 경우에 들어 게시 순서를 정할 수 없다. 그래서 옛 글의 {@code TERM_TRANSFER} 항목으로 막는다({@link
     * #keptTermFailure}). 옛 글이 이 공개 밖에 있으면 data-model §7-2대로 넘기기가 그 초안 행을 지우고, 남은 용어 블록은 그 글의 다음
     * 게시에서 걸린다.
     *
     * @return 옛 소유 글마다 넘겨줄 용어와 넘겨받는 글. 해당하는 글이 없으면 빈 맵이다
     */
    public static Map<ArticleId, Map<TermId, ArticleId>> termsKeptByGivers(
            List<LearningArticle> releaseSet) {
        Map<ArticleId, LearningArticle> members = new HashMap<>();
        releaseSet.forEach(article -> members.put(article.articleId(), article));
        Map<ArticleId, Map<TermId, ArticleId>> kept = new LinkedHashMap<>();
        for (LearningArticle receiver : releaseSet) {
            for (LearningTerm term : receiver.terms()) {
                if (!term.transferring()) {
                    continue;
                }
                LearningArticle giver = members.get(term.transferFrom());
                if (giver != null
                        && giver.terms().stream()
                                .anyMatch(own -> own.termId().equals(term.termId()))) {
                    kept.computeIfAbsent(giver.articleId(), key -> new LinkedHashMap<>())
                            .put(term.termId(), receiver.articleId());
                }
            }
        }
        return kept;
    }

    /**
     * 게시 순서(data-model §10-3 ⑤). 같은 공개 안에서 다른 글이 용어를 넘겨받는 옛 소유 글은 넘겨받는 글보다 먼저 게시한다. 그래야 옛 글의 판에
     * 운영자가 미리 검사에서 확인한 초안 번호가 남는다. 넘기기가 옛 글의 초안 번호를 먼저 올리면 판에는 운영자가 보지 않은 번호가 남는다. 그 밖의 순서는 공개 집합의
     * 순서다.
     *
     * <p>넘겨받기가 고리를 이루면(서로의 용어를 넘겨받음) 고리 안에서는 지킬 순서가 없다. 옛 소유 글이 넘겨줄 용어를 초안에 남긴 고리는 판정이 먼저
     * 막지만({@link #termsKeptByGivers}), 서로 넘겨줄 용어를 이미 초안에서 뺀 고리는 판정을 통과한다. 그때는 고리 안의 글 하나만 먼저 게시해
     * 고리를 끊고 나머지는 다시 순서를 따진다. 고리 밖에서 그 고리의 글에게서 넘겨받는 글은 계속 옛 소유 글 다음에 게시된다.
     *
     * @param releaseSet 공개 집합, 단계 안 순서
     */
    public static List<LearningArticle> publishOrder(List<LearningArticle> releaseSet) {
        List<LearningArticle> pending = new ArrayList<>(releaseSet);
        List<LearningArticle> ordered = new ArrayList<>();
        while (!pending.isEmpty()) {
            Map<ArticleId, LearningArticle> waiting = new HashMap<>();
            pending.forEach(article -> waiting.put(article.articleId(), article));
            // 넘겨줄 옛 글이 아직 게시를 기다리는 글은 다음 차례로 미룬다.
            List<LearningArticle> ready =
                    pending.stream()
                            .filter(article -> pendingGivers(article, waiting).findAny().isEmpty())
                            .toList();
            if (ready.isEmpty()) {
                ready = List.of(onCycle(pending.getFirst(), waiting));
            }
            ordered.addAll(ready);
            pending.removeAll(ready);
        }
        return ordered;
    }

    /** 아직 게시를 기다리는 옛 소유 글. */
    private static Stream<LearningArticle> pendingGivers(
            LearningArticle article, Map<ArticleId, LearningArticle> waiting) {
        return article.terms().stream()
                .filter(LearningTerm::transferring)
                .map(term -> waiting.get(term.transferFrom()))
                .filter(Objects::nonNull);
    }

    /**
     * 게시할 수 있는 글이 없을 때 고리 위의 글 하나. 기다리는 글은 모두 기다리는 옛 글이 있으므로 옛 글을 따라가면 이미 지난 글로 돌아오고, 그 글이 고리 위에
     * 있다. 고리 밖에서 고리를 기다리는 글을 고르면 그 글이 고리의 옛 글보다 먼저 게시된다.
     */
    private static LearningArticle onCycle(
            LearningArticle start, Map<ArticleId, LearningArticle> waiting) {
        Set<ArticleId> visited = new HashSet<>();
        LearningArticle current = start;
        while (visited.add(current.articleId())) {
            current =
                    pendingGivers(current, waiting)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "a waiting article has no waiting giver"));
        }
        return current;
    }

    /** {@link #termsKeptByGivers}가 찾은 용어 하나의 항목. 옛 소유 글에 붙고 위치는 그 용어다. */
    public static PublishCheckFailure keptTermFailure(TermId termId, ArticleId receiver) {
        return new PublishCheckFailure(
                Check.TERM_TRANSFER,
                CheckLocation.term(termId),
                null,
                "같은 단계의 '"
                        + receiver.value()
                        + "'가 '"
                        + termId.value()
                        + "'를 넘겨받습니다. 이 글의 초안에서 그 용어와 용어 블록을 뺀 뒤 공개합니다.");
    }

    /**
     * 이 공개가 변경 기록에 올리는 편수(R27). 공개된 단계에 처음 공개되는 글만 센다. 그 가운데 LEGACY 판이 있던 글이 다시 쓴 글이고 나머지가 새 글이다.
     * 공개된 단계에서 옮겨 온 글은 어느 쪽에도 세지 않는다.
     *
     * @param newCount 새 글 편수
     * @param rewrittenTitles 다시 쓴 글의 그때 제목, 넘긴 순서
     */
    public record Tally(int newCount, List<String> rewrittenTitles) {

        public Tally {
            rewrittenTitles = List.copyOf(rewrittenTitles);
        }

        public static Tally empty() {
            return new Tally(0, List.of());
        }

        /**
         * @param firstRelease 이 공개 전에 {@code first_released_at}이 비어 있었다
         * @param hadLegacyRevision 이 공개 전에 그 글의 LEGACY 판이 있었다
         * @param title 그때 제목
         */
        public Tally add(boolean firstRelease, boolean hadLegacyRevision, String title) {
            if (!firstRelease) {
                return this;
            }
            if (!hadLegacyRevision) {
                return new Tally(newCount + 1, rewrittenTitles);
            }
            List<String> titles = new ArrayList<>(rewrittenTitles);
            titles.add(title);
            return new Tally(newCount, titles);
        }
    }

    private static boolean previousReleased(LearningStage stage, List<LearningStage> stages) {
        return stages.stream()
                .filter(candidate -> candidate.number() < stage.number())
                .allMatch(LearningStage::released);
    }
}
