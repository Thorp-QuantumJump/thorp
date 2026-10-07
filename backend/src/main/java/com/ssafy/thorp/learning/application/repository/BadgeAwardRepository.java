package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.vo.BadgeKey;
import java.time.Instant;
import java.util.Map;

/** 받은 배지({@code learning_badge_award}, data-model §11-4). 추가만 한다. 콘텐츠가 바뀌어도 지우지 않는다(FR-024). */
public interface BadgeAwardRepository {

    /** 그 사용자가 받은 배지와 받은 시각. */
    Map<BadgeKey, Instant> findAll(long userId);

    /**
     * 받지 않은 배지일 때만 넣는다({@code ON CONFLICT DO NOTHING}).
     *
     * @return 이번 요청이 넣었다
     */
    boolean award(long userId, BadgeKey badgeKey, Instant at);

    /** 그 사용자의 배지를 모두 지운다('학습 초기화'). */
    void deleteAllForUser(long userId);
}
