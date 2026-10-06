package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.PublishCheckFailure.Check;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.util.ArrayList;
import java.util.List;

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
        return article.placement().stageKey().equals(stageKey)
                || (article.publishedPlacement() != null
                        && article.publishedPlacement().stageKey().equals(stageKey));
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
