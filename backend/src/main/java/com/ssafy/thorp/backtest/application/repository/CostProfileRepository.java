package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.CostProfile;
import java.util.Optional;

/**
 * 비용 조건 등록표 조회 포트(spec 004 research R-07). 구현은 {@code infrastructure.persistence}에 둔다.
 *
 * <p>읽기 전용이다. 비용 가정이 바뀌면 값을 고치는 대신 새 버전 행을 넣는다 — 이미 끝난 실행을 나중 비용으로 재해석하면 재현이 깨진다.
 */
public interface CostProfileRepository {

    Optional<CostProfile> find(String version);
}
