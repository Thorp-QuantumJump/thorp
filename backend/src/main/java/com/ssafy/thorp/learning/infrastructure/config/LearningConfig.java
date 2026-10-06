package com.ssafy.thorp.learning.infrastructure.config;

import com.ssafy.thorp.learning.application.port.InstitutionNoteReviewPeriod;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 배우기 설정 등록(spec 013 T158).
 *
 * <p>여기서 빈으로 만들어야 기동 시점에 {@code @Validated} 검사가 돌아 값 누락이 드러난다. application 계층은 infrastructure를 참조할
 * 수 없으므로({@code ArchitectureTest} 계층 규칙) 서비스가 쓸 불변 값을 함께 둔다. {@code MyRecordConfig}와 같은 방식이다.
 */
@Configuration
@EnableConfigurationProperties(LearningProperties.class)
public class LearningConfig {

    @Bean
    public InstitutionNoteReviewPeriod institutionNoteReviewPeriod(LearningProperties properties) {
        return new InstitutionNoteReviewPeriod(properties.getInstitutionNoteReviewDays());
    }
}
