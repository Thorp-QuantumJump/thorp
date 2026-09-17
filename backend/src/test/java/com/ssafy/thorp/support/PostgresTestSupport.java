package com.ssafy.thorp.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DB에 붙는 테스트의 공통 기반. 운영과 같은 PostgreSQL 컨테이너를 띄우고, 스키마도 운영과 같은 경로로 만든다.
 *
 * <p>인메모리 DB는 쓰지 않는다. jsonb·시퀀스·타임존 타입처럼 방언이 갈리는 부분은 대체 DB에서 통과해도 운영에서 깨질 수 있고, 이번 {@code
 * backtest_job} 스키마 불일치가 바로 그런 사고였다.
 *
 * <p>Flyway가 마이그레이션을 적용하고 Hibernate는 {@code ddl-auto=validate}로 검증만 하므로, 마이그레이션과 Entity 매핑이 어긋나면
 * 상속한 테스트의 컨텍스트 로딩부터 실패한다.
 *
 * <p>Docker가 없는 환경에서는 상속한 테스트를 건너뛴다. 이미지 태그는 운영 Compose({@code postgres:16-alpine})와 맞춘다.
 *
 * <p><b>컨테이너를 {@code @Container}로 관리하지 않고 싱글턴으로 둔다.</b> {@code @Container} static은 테스트 <b>클래스마다</b>
 * 컨테이너를 stop하는데 Spring은 애플리케이션 컨텍스트를 캐시해 다음 클래스에서 재사용한다. 설정이 같은 {@code @SpringBootTest} 클래스가 둘 이상이면
 * 뒤에 오는 쪽이 <b>죽은 컨테이너를 가리키는 커넥션 풀</b>을 물려받아 {@code Connection is not available ... (total=0)}으로
 * 30초씩 타임아웃한다. 한 번 띄우고 stop하지 않으면 순서와 무관하게 안전하고, 클래스마다 컨테이너를 다시 띄우지 않아 전체 테스트도 빨라진다. 정리는
 * Testcontainers의 Ryuk가 JVM 종료 시 한다.
 *
 * <p>시작을 {@code @BeforeAll}에서 하는 이유: static 초기화 블록에서 하면 Docker가 없는 환경에서 클래스 로딩 시점에 터져 {@code
 * disabledWithoutDocker}의 skip보다 먼저 실패한다. {@code @BeforeAll}은 실행 조건 평가 뒤에 돌고 Spring 컨텍스트 생성보다는 앞이다.
 */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            // 컨텍스트마다 Hikari 기본 10개를 잡으면 DB 테스트 클래스가 늘수록
            // PostgreSQL 기본 max_connections(100)를 넘겨 "too many clients already"가 난다.
            // 테스트는 단일 스레드라 컨텍스트당 2개로 충분하다.
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.minimum-idle=0",
            // 배우기 관리 IT(spec 013 B02)의 관리 계정. 클래스마다 속성을 따로 주면 컨텍스트가 갈라져 공용 컨테이너의
            // 연결이 모자라므로 여기 한 곳에 둔다. 다른 도메인은 이 값을 읽지 않는다.
            "thorp.learning.admin-user-ids=7"
        })
public abstract class PostgresTestSupport {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void startContainerOnce() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    @Autowired private JdbcTemplate seedJdbcTemplate;

    /**
     * 검사마다 시장 자료를 다시 심는다.
     *
     * <p>{@code MarketMetadataPort} 의 운영 구현이 DB 를 읽으므로, 심어 두지 않으면 접수 경로를 타는 검사가 전부 "대응표가 없다"로 막힌다.
     * 마이그레이션으로 한 번만 심지 않는 까닭은 {@link MarketCatalogSeed} 의 설명에 있다 — 요약하면 자기 자료를 직접 넣고 지우는 검사와 부딪히기
     * 때문이다.
     *
     * <p>JUnit 이 상위 클래스의 {@code @BeforeEach} 를 먼저 돌리므로, 자기 자료를 따로 준비하는 검사는 그 위에서 마음대로 치우고 넣어도 된다.
     * 다음 검사 전에 여기서 다시 심긴다.
     */
    @BeforeEach
    void seedMarketCatalog() {
        MarketCatalogSeed.seed(seedJdbcTemplate);
    }
}
