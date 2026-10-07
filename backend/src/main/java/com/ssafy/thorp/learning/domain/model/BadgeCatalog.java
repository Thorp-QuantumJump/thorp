package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.BadgeDefinition.Family;
import com.ssafy.thorp.learning.domain.model.BadgeDefinition.Measure;
import com.ssafy.thorp.learning.domain.model.ContentScope.StageScope;
import com.ssafy.thorp.learning.domain.vo.BadgeKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 배지 31개(data-model §12-4, FR-024, research R30). 기준은 spec과 함께 바뀌므로 자원 파일이 아니라 코드와 테스트로 고정한다.
 *
 * <p>누가 이미 받았을 수 있는 기준은 바꾸지 않는다. 글이 늘면 사다리에 단계를 더한다. 숨긴 배지·무작위 보상·기간 한정·연속 출석·시간대 배지를 두지 않는다. 이름에
 * 투자 실력·수익·순위로 읽히는 낱말을 쓰지 않는다.
 *
 * @param definitions 갈래 순서(레벨·읽은 글·첫 답 정답·모두 맞힘·단계 완주·익힌 용어·특별), 갈래 안에서는 사다리 순서
 */
public record BadgeCatalog(List<BadgeDefinition> definitions) {

    private static final int[] LEVELS = {2, 3, 4, 5, 6, 7, 8};
    private static final int[] READ = {1, 5, 10, 20};
    private static final int READ_ALL = 27;
    private static final int[] CORRECT = {1, 10, 25, 50};
    private static final int[] PERFECT = {1, 5, 15};
    private static final int[] TERMS = {1, 10, 25};

    public BadgeCatalog {
        definitions = List.copyOf(definitions);
    }

    /**
     * @param stages 여섯 단계, 번호 순서. 단계 완주 배지의 키와 이름이 단계에서 온다
     */
    public static BadgeCatalog of(List<StageScope> stages) {
        List<BadgeDefinition> all = new ArrayList<>();
        for (int level : LEVELS) {
            all.add(
                    plain(
                            "level-" + level,
                            Family.LEVEL,
                            "레벨 " + level,
                            "경험치 "
                                    + String.format(Locale.ROOT, "%,d", Level.threshold(level))
                                    + "에 닿습니다",
                            Measure.LEVEL,
                            level));
        }
        for (int count : READ) {
            all.add(read(count, Measure.READ));
        }
        all.add(read(READ_ALL, Measure.READ_ALL_STAGES));
        for (int count : CORRECT) {
            all.add(
                    plain(
                            "correct-" + count,
                            Family.CORRECT,
                            "첫 답 정답 " + count + "개",
                            "확인 문제를 첫 답에서 " + count + "개 맞힙니다",
                            Measure.CORRECT,
                            count));
        }
        for (int count : PERFECT) {
            all.add(
                    plain(
                            "perfect-" + count,
                            Family.PERFECT,
                            "모두 맞힘 " + count + "편",
                            "한 글의 확인 문제를 첫 답에서 모두 맞힌 글이 " + count + "편이 됩니다",
                            Measure.PERFECT,
                            count));
        }
        for (StageScope stage : stages) {
            all.add(
                    new BadgeDefinition(
                            new BadgeKey("stage-" + stage.stageKey().value()),
                            Family.STAGE,
                            stage.number() + "단계 완주",
                            stage.number() + "단계의 글을 모두 읽습니다",
                            Measure.STAGE,
                            0,
                            stage.stageKey(),
                            stage.number()));
        }
        for (int count : TERMS) {
            all.add(
                    plain(
                            "terms-" + count,
                            Family.TERMS,
                            "익힌 용어 " + count + "개",
                            "익힌 용어가 " + count + "개가 됩니다",
                            Measure.TERMS,
                            count));
        }
        all.add(
                plain(
                        "terms-all",
                        Family.TERMS,
                        "그때 게시된 용어 전체",
                        "그때 게시된 용어를 모두 익힙니다",
                        Measure.TERMS_ALL,
                        0));
        all.add(
                plain(
                        "persistence",
                        Family.SPECIAL,
                        "끈기",
                        "첫 답에서 하나도 맞히지 못한 글을 다시 풀어 익힘으로 바꿉니다",
                        Measure.PERSISTENCE,
                        0));
        all.add(
                plain(
                        "exploration",
                        Family.SPECIAL,
                        "탐험",
                        "모든 단계에서 한 편 이상 읽습니다",
                        Measure.EXPLORATION,
                        0));
        return new BadgeCatalog(all);
    }

    private static BadgeDefinition read(int count, Measure measure) {
        return plain(
                "read-" + count,
                Family.READ,
                "읽은 글 " + count + "편",
                "글 " + count + "편을 끝까지 읽습니다",
                measure,
                count);
    }

    private static BadgeDefinition plain(
            String key, Family family, String name, String criterion, Measure measure, int target) {
        return new BadgeDefinition(
                new BadgeKey(key), family, name, criterion, measure, target, null, null);
    }
}
