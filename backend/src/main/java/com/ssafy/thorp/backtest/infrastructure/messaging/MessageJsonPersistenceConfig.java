package com.ssafy.thorp.backtest.infrastructure.messaging;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON 컬럼({@code @JdbcTypeCode(SqlTypes.JSON)})의 직렬화를 메시지 ObjectMapper에 맡긴다.
 *
 * <p>결과 저장소는 계약 DTO({@code EngineOutput} 등)를 그대로 JSON 컬럼에 담는다. Hibernate 기본 ObjectMapper를 쓰면
 * decimal이 JSON 숫자로 쓰이고, PostgreSQL {@code jsonb}는 숫자를 정규화해 지수 표기·scale이 바뀔 수 있다. 메시지 ObjectMapper는
 * decimal을 plain 문자열로 쓰므로 저장된 값이 계약이 준 문자열과 같고 scale이 보존된다. 날짜도 ISO 문자열이 되어 DB 안의 JSON이 메시지 JSON과
 * 같은 표기를 갖는다.
 *
 * <p>이 설정 때문에 결과 컬럼을 직접 읽어 파싱하는 쪽도 같은 매퍼를 써야 한다(decimal이 문자열로 저장되므로). 그래서 {@code
 * BacktestRabbitConfig#messageObjectMapper()}는 infrastructure 안에서 공유하도록 public이다. 다른 계층으로 새지 않는 것은
 * ArchUnit 계층 규칙이 보장한다 — 그 메서드의 javadoc 참고.
 */
@Configuration
public class MessageJsonPersistenceConfig {

    @Bean
    public HibernatePropertiesCustomizer messageJsonFormatMapper() {
        return properties ->
                properties.put(
                        AvailableSettings.JSON_FORMAT_MAPPER,
                        new JacksonJsonFormatMapper(BacktestRabbitConfig.messageObjectMapper()));
    }
}
