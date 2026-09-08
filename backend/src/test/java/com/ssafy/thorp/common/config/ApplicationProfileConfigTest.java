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

    @Test
    void devProfileUsesDeploymentEnvironmentVariables() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/deploy-db",
                        "SPRING_DATASOURCE_USERNAME=deploy-user",
                        "SPRING_DATASOURCE_PASSWORD=deploy-password",
                        "SPRING_RABBITMQ_HOST=rabbitmq",
                        "SPRING_RABBITMQ_PORT=5672",
                        "SPRING_RABBITMQ_USERNAME=deploy-user",
                        "SPRING_RABBITMQ_PASSWORD=deploy-password",
                        "SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getEnvironment().getActiveProfiles())
                                    .containsExactly("dev");
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
}
