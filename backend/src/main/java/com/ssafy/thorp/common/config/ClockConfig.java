package com.ssafy.thorp.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각 의존을 주입 가능하게 만드는 {@link Clock} 빈.
 *
 * <p>{@code backtestId}의 날짜 접두({@code BT-yyyyMMdd-…})는 사람이 읽는 값이라 KST 기준으로 만든다. 서버 기본 시간대에 기대지 않도록
 * ZoneId를 명시하고, 테스트는 고정 Clock으로 대체한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
