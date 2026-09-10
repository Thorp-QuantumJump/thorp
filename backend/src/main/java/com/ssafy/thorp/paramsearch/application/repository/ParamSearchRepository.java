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

    Optional<ParamSearch> findBySearchId(String searchId);
}
