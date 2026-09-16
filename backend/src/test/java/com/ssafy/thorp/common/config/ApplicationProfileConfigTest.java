package com.ssafy.thorp.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApplicationProfileConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void localProfileIsUsedByDefault() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getDefaultProfiles())
                            .containsExactly("local");
                    assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                            .isEqualTo("jdbc:postgresql://localhost:5432/thorp");
                    assertThat(context.getEnvironment().getProperty("spring.rabbitmq.host"))
                            .isEqualTo("localhost");
                    assertThat(
                                    context.getEnvironment()
                                            .getProperty("spring.kafka.bootstrap-servers"))
                            .isEqualTo("localhost:9092");
                });
    }

    /** 로컬은 Hibernate가 스키마를 갱신하고 Flyway는 꺼져 있어야 한다. 둘이 동시에 스키마를 바꾸면 안 된다. */
    @Test
    void localProfileLetsHibernateManageSchemaWithoutFlyway() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(
                                    context.getEnvironment()
                                            .getProperty("spring.jpa.hibernate.ddl-auto"))
                            .isEqualTo("update");
                    assertThat(context.getEnvironment().getProperty("spring.flyway.enabled"))
                            .isEqualTo("false");
                    assertThat(
                                    context.getEnvironment()
                                            .getProperty("spring.session.jdbc.initialize-schema"))
                            .isEqualTo("always");
                    assertThat(
                                    context.getEnvironment()
                                            .getProperty("server.servlet.session.cookie.secure"))
                            .isEqualTo("false");
                });
    }

    @Test
    void sharedSessionConfigUsesServerManagedThirtyMinuteSession() {
        contextRunner
                .withPropertyValues("spring.profiles.active=none")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var environment = context.getEnvironment();
                            assertThat(environment.getProperty("spring.session.timeout"))
                                    .isEqualTo("30m");
                            assertThat(
                                            environment.getProperty(
                                                    "server.servlet.session.cookie.name"))
                                    .isEqualTo("THORP_SESSION");
                            assertThat(
                                            environment.getProperty(
                                                    "server.servlet.session.cookie.http-only"))
                                    .isEqualTo("true");
                            assertThat(
                                            environment.getProperty(
                                                    "server.servlet.session.cookie.same-site"))
                                    .isEqualTo("lax");
                            assertThat(
                                            environment.getProperty(
                                                    "server.servlet.session.cookie.path"))
                                    .isEqualTo("/");
                        });
    }

    /** 공통 설정에 ddl-auto가 남아 있으면 프로필을 지정하지 않은 실행에서 Hibernate가 스키마를 건드린다. */
    @Test
    void sharedConfigDoesNotDefineDdlAuto() {
        contextRunner
                .withPropertyValues("spring.profiles.active=none")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("spring.jpa.hibernate.ddl-auto"))
                                    .isNull();
                        });
    }

    @Test
    void prodProfileUsesDeploymentEnvironmentVariables() {
        prodContextRunner()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getEnvironment().getActiveProfiles())
                                    .containsExactly("prod");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("spring.datasource.url"))
                                    .isEqualTo("jdbc:postgresql://postgres:5432/deploy-db");
                            assertThat(context.getEnvironment().getProperty("spring.rabbitmq.host"))
                                    .isEqualTo("rabbitmq");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("spring.kafka.bootstrap-servers"))
                                    .isEqualTo("kafka:29092");
                        });
    }

    /**
     * 운영에서는 Flyway가 스키마를 만들고 Hibernate는 검증만 한다. baseline은 자동으로 켜지 않고, 스키마를 통째로 지우는 clean은 막아 둔다.
     *
     * <p>clean-disabled는 Flyway 기본값도 true지만 설정에 적고 여기서 단언한다. 기본값에 기대면 도구 버전을 올릴 때 조용히 바뀌어도 드러나지
     * 않는다.
     */
    @Test
    void prodProfileDelegatesSchemaToFlywayAndValidatesWithHibernate() {
        prodContextRunner()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var environment = context.getEnvironment();
                            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                                    .isEqualTo("validate");
                            assertThat(environment.getProperty("spring.flyway.enabled"))
                                    .isEqualTo("true");
                            assertThat(environment.getProperty("spring.flyway.locations"))
                                    .isEqualTo("classpath:db/migration");
                            assertThat(environment.getProperty("spring.flyway.baseline-on-migrate"))
                                    .isEqualTo("false");
                            assertThat(environment.getProperty("spring.flyway.validate-on-migrate"))
                                    .isEqualTo("true");
                            assertThat(environment.getProperty("spring.flyway.clean-disabled"))
                                    .isEqualTo("true");
                            assertThat(
                                            environment.getProperty(
                                                    "spring.session.jdbc.initialize-schema"))
                                    .isEqualTo("never");
                            assertThat(
                                            environment.getProperty(
                                                    "server.servlet.session.cookie.secure"))
                                    .isEqualTo("true");
                        });
    }

    /**
     * 운영은 nginx가 TLS를 끝내고 평문으로 넘기므로 이 값이 없으면 {@code request.isSecure()}가 false다.
     *
     * <p>세션 쿠키는 위에서 {@code secure: true}로 못 박지만 XSRF-TOKEN 쿠키는 요청이 HTTPS인지 보고 정하기 때문에, 이 값이 빠지면
     * 운영에서만 {@code Secure} 없는 CSRF 쿠키가 나간다. 동작 자체는 {@code ProxySecureCookieIntegrationTest}가 지킨다.
     */
    @Test
    void prodProfileTrustsTheReverseProxyForwardedHeaders() {
        prodContextRunner()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("server.forward-headers-strategy"))
                                    .isEqualTo("framework");
                        });
    }

    private ApplicationContextRunner prodContextRunner() {
        return contextRunner.withPropertyValues(
                "spring.profiles.active=prod",
                "SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/deploy-db",
                "SPRING_DATASOURCE_USERNAME=deploy-user",
                "SPRING_DATASOURCE_PASSWORD=deploy-password",
                "SPRING_RABBITMQ_HOST=rabbitmq",
                "SPRING_RABBITMQ_PORT=5672",
                "SPRING_RABBITMQ_USERNAME=deploy-user",
                "SPRING_RABBITMQ_PASSWORD=deploy-password",
                "SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092");
    }
}
