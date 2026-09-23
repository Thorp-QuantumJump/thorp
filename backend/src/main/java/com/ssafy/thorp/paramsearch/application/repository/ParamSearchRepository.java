package com.ssafy.thorp.paramsearch.application.repository;

import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import java.util.Optional;

/** 탐색 묶음 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다. */
public interface ParamSearchRepository {

    /**
     * 신규 묶음 접수 전용. 저장하면서 외부 식별자 {@code searchId}를 부여하고, id와 searchId가 채워진 묶음을 돌려준다.
     *
     * <p>id가 이미 있는 묶음이면 {@link IllegalArgumentException}. 이후 갱신은 {@link #update(ParamSearch)}.
     */
    ParamSearch save(ParamSearch paramSearch);

    /**
     * 저장된 묶음의 진행 상태를 반영한다.
     *
     * <p>접수 시점 스냅샷(탐색 공간·선정 규칙·한도·참조 버전)은 갱신하지 않는다 — 이후 바뀌지 않아야 하는 값이라 갱신 경로를 두지 않았다(FR-013c, 원칙
     * VII).
     */
    ParamSearch update(ParamSearch paramSearch);

    Optional<ParamSearch> findById(Long id);

    /**
     * 묶음 행을 잠그고 읽는다. 집계 갱신과 발행 창 계산이 이 락 안에서 일어난다(내부 계약 §4 동시 수신 경쟁).
     *
     * <p>락 없이 처리하면 동시에 도착한 두 결과가 같은 집계를 읽어 각자 갱신하고, 늦게 쓴 쪽이 상대의 갱신을 되돌린다. 발행 창도 마찬가지로 둘 다 "자리가 있다"고
     * 판단해 창을 넘겨 발행한다.
     */
    Optional<ParamSearch> findByIdForUpdate(Long id);

    Optional<ParamSearch> findBySearchId(String searchId);
}
