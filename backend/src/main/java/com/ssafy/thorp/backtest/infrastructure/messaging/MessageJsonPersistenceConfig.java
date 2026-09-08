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
 * <p>ObjectMapper 자체는 package-private로 둔다. 이 설정은 그것을 Hibernate에 연결만 하고 다른 코드에 노출하지 않는다.
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
