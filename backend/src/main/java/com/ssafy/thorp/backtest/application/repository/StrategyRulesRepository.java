package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import java.util.List;
import java.util.Optional;

/**
 * 전략 규칙 등록표 조회 포트(spec 004 research R-08). 구현은 {@code infrastructure.persistence}에 둔다.
 *
 * <p>읽기 전용이다. 규칙 등록·갱신은 마이그레이션으로 한다 — 실행 중인 시스템이 규칙을 바꾸면 같은 버전 문자열이 서로 다른 계산을 가리키게 되고 재현이 깨진다(원칙
 * VIII).
 */
public interface StrategyRulesRepository {

    Optional<StrategyRules> find(String strategyId, String rulesVersion);

    /** 대체된 옛 버전까지 전부. 전략 하나에 여러 행이 있을 수 있다. */
    List<StrategyRules> findAll();

    /**
     * 대체되지 않은 행만. 전략마다 하나다(T029, 부분 유니크 인덱스 {@code uk_strategy_rules_current}).
     *
     * <p>화면·운영이 "지금 무엇을 고를 수 있는가"를 보여줄 때 쓴다.
     */
    List<StrategyRules> findCurrent();

    /** 그 전략의 대체되지 않은 행. 없으면 empty. */
    Optional<StrategyRules> findCurrent(String strategyId);
}
