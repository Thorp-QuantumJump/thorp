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

    /** 화면·운영이 "무엇을 실행할 수 있는가"를 보여줄 때 쓴다. */
    List<StrategyRules> findAll();
}
