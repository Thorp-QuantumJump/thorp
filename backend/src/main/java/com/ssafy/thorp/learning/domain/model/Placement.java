package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.util.Objects;

/**
 * 글의 단계 배치(research R15). 초안 배치는 운영자가 고치고, 공개 배치는 게시가 초안 배치를 옮겨 적는다.
 *
 * @param position 단계 안 순서 1..99. 유일하지 않다. 같으면 {@code articleId} 순이다
 */
public record Placement(StageKey stageKey, int position) {

    public static final int MIN_POSITION = 1;
    public static final int MAX_POSITION = 99;

    public Placement {
        Objects.requireNonNull(stageKey, "stageKey must not be null");
        if (position < MIN_POSITION || position > MAX_POSITION) {
            throw new IllegalArgumentException(
                    "stage position must be within "
                            + MIN_POSITION
                            + ".."
                            + MAX_POSITION
                            + ": "
                            + position);
        }
    }
}
