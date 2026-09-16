package com.ssafy.thorp.marketflow.infrastructure.config;

import com.ssafy.thorp.marketflow.domain.model.NarrativeFormatter;
import com.ssafy.thorp.marketflow.domain.model.NarrativeSubjectSelector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring을 모르는 서술 도메인 규칙을 애플리케이션 빈으로 연결한다. */
@Configuration
public class MarketFlowConfig {

    @Bean
    NarrativeSubjectSelector narrativeSubjectSelector() {
        return new NarrativeSubjectSelector();
    }

    @Bean
    NarrativeFormatter narrativeFormatter() {
        return new NarrativeFormatter();
    }
}
