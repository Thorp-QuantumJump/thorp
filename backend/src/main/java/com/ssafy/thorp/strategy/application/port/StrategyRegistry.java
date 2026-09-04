package com.ssafy.thorp.strategy.application.port;

import com.ssafy.thorp.strategy.domain.model.StrategyCatalog;

/**
 * 사용 가능한 전략 정의의 목록을 관리하는 지점.
 *
 * <p>전략이 어디에 어떤 형태로 보관되는지는 구현이 정한다. 애플리케이션은 "지금 쓸 수 있는 전략 카탈로그"만 알면 되고, 형식을 위반한 정의를 걸러내는 일도 구현의
 * 책임이다(FR-017).
 */
public interface StrategyRegistry {

    /** 현재 등재된 전략 카탈로그. 기동 이후 바뀌지 않는다. */
    StrategyCatalog catalog();
}
