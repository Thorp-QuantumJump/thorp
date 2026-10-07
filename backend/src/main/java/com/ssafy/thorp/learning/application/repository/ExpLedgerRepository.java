package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.model.ExpEntry;
import com.ssafy.thorp.learning.domain.model.ExpLedger;

/**
 * 경험치 장부({@code learning_exp_entry}, data-model §11-3). 추가만 한다. 줄을 고치거나 하나만 지우는 메서드가 없다. 레벨은 장부
 * 합계로만 정한다(FR-028·FR-029).
 */
public interface ExpLedgerRepository {

    /** 그 사용자의 장부. 쌓인 순서다. */
    ExpLedger find(long userId);

    /**
     * 같은 (종류, 대상 키)가 없을 때만 넣는다({@code ON CONFLICT DO NOTHING}). 같은 일로 경험치를 두 번 받지 않는다(SC-015).
     *
     * @return 이번 요청이 넣었다
     */
    boolean add(long userId, ExpEntry entry);

    /** 그 사용자의 줄을 모두 지운다('학습 초기화'). */
    void deleteAllForUser(long userId);
}
