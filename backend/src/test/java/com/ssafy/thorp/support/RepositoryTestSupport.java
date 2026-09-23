package com.ssafy.thorp.support;

import com.ssafy.thorp.backtest.infrastructure.messaging.MessageJsonPersistenceConfig;
import com.ssafy.thorp.backtest.infrastructure.persistence.BacktestRequestRepositoryImpl;
import com.ssafy.thorp.backtest.infrastructure.persistence.BacktestResultRepositoryImpl;
import com.ssafy.thorp.backtest.infrastructure.persistence.BacktestRunRepositoryImpl;
import com.ssafy.thorp.backtest.infrastructure.persistence.CostProfileRepositoryImpl;
import com.ssafy.thorp.backtest.infrastructure.persistence.StrategyRulesRepositoryImpl;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.learning.infrastructure.persistence.ArticleRevisionRepositoryImpl;
import com.ssafy.thorp.learning.infrastructure.persistence.LearningArticleRepositoryImpl;
import com.ssafy.thorp.learning.infrastructure.persistence.LearningTermRepositoryImpl;
import com.ssafy.thorp.learning.infrastructure.persistence.PublicationEventRepositoryImpl;
import com.ssafy.thorp.learning.infrastructure.persistence.PublishedTermRepositoryImpl;
import com.ssafy.thorp.marketflow.infrastructure.persistence.MarketFlowFieldSummaryRepositoryImpl;
import com.ssafy.thorp.myrecord.infrastructure.persistence.MyRecordRepositoryImpl;
import com.ssafy.thorp.myrecord.infrastructure.persistence.RegimeSearchLogWriterImpl;
import com.ssafy.thorp.paramsearch.infrastructure.persistence.ParamSearchRepositoryImpl;
import com.ssafy.thorp.paramsearch.infrastructure.persistence.SearchCandidateRepositoryImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 저장소 구현을 JPA 슬라이스로 띄우는 테스트의 공통 기반. <b>상속한 테스트는 Spring 컨텍스트 하나를 함께 쓴다.</b>
 *
 * <p>{@code @DataJpaTest}도 {@code @Import} 목록과 중첩 설정 클래스가 하나라도 다르면 컨텍스트를 새로 만든다. 그래서 저장소 구현을 도메인과
 * 상관없이 여기서 한꺼번에 올리고, 상속한 테스트는 {@code @DataJpaTest}·{@code @Import}·시계 설정을 따로 두지 않는다. 새 저장소 구현을
 * 테스트하려면 이 목록에 더한다.
 *
 * <p>{@link MessageJsonPersistenceConfig}를 모두에게 올린다. 없으면 JSON 컬럼이 Hibernate 기본 매퍼로 저장되는데, 그 매퍼는 테스트
 * 클래스패스에만 있는 jackson-module-scala를 끌어와 decimal을 숫자로 쓰는 등 운영과 다르게 동작한다.
 *
 * <p>뉴스 저장소 테스트는 뉴스 설정 대역을 테스트마다 다르게 두므로 이 기반을 쓰지 않는다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    MessageJsonPersistenceConfig.class,
    RepositoryTestSupport.FixedClock.class,
    BacktestRunRepositoryImpl.class,
    BacktestRequestRepositoryImpl.class,
    BacktestResultRepositoryImpl.class,
    CostProfileRepositoryImpl.class,
    StrategyRulesRepositoryImpl.class,
    LearningArticleRepositoryImpl.class,
    LearningTermRepositoryImpl.class,
    ArticleRevisionRepositoryImpl.class,
    PublicationEventRepositoryImpl.class,
    PublishedTermRepositoryImpl.class,
    MarketFlowFieldSummaryRepositoryImpl.class,
    ParamSearchRepositoryImpl.class,
    SearchCandidateRepositoryImpl.class,
    MyRecordRepositoryImpl.class,
    RegimeSearchLogWriterImpl.class
})
public abstract class RepositoryTestSupport extends PostgresTestSupport {

    /** 저장소가 식별자의 날짜를 이 시계로 붙인다. 날짜를 단언하는 검사는 값을 적지 말고 {@link #idDate()}를 쓴다. */
    public static final Instant NOW = Instant.parse("2026-09-07T03:00:00Z");

    /** 식별자의 날짜는 거래소 현지 기준이다. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 이 시계로 만들어진 식별자의 날짜 부분({@code BT-<여기>-000001}). */
    public static String idDate() {
        return DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZONE).format(NOW);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZONE);
        }
    }
}
