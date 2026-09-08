package com.ssafy.thorp.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
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
 */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.baseline-on-migrate=false",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
public abstract class PostgresTestSupport {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
