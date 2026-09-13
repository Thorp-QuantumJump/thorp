package com.ssafy.thorp.news.infrastructure.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * 뉴스 도메인 설정과 버전 관리되는 등록표 로딩.
 *
 * <p>등록표는 기동 시 한 번 읽어 불변 객체로 둔다. 형식이나 등록 조건을 어기면 기동을 실패시켜 잘못된 채널이 조용히 수집되지 않게 한다(spec FR-009).
 *
 * <p>등록표 전용 ObjectMapper를 쓴다. 웹 MVC ObjectMapper를 공유하면 이 파일의 파싱 정책 변경이 API 응답 계약에 번진다.
 */
@Configuration
@EnableConfigurationProperties(NewsProperties.class)
// 수집 스케줄러가 돌려면 필요하다. 저장소에 @EnableScheduling이 없어 여기서 켠다.
@org.springframework.scheduling.annotation.EnableScheduling
public class NewsConfig {

    static final String PUBLISHER_REGISTRY_PATH = "news/news-publishers-v1.json";
    static final String LINK_REGISTRY_PATH = "news/news-link-v1.json";

    @Bean
    public NewsPublisherRegistry newsPublisherRegistry(NewsProperties properties) {
        NewsPublisherRegistry registry =
                read(new ClassPathResource(PUBLISHER_REGISTRY_PATH), NewsPublisherRegistry.class);
        if (registry.publishers().size() > properties.maxPublishers()) {
            throw new IllegalStateException(
                    "등록 매체 수가 계획 상한을 넘었다: "
                            + registry.publishers().size()
                            + " > "
                            + properties.maxPublishers());
        }
        return registry;
    }

    @Bean
    public NewsLinkRegistry newsLinkRegistry() {
        return read(new ClassPathResource(LINK_REGISTRY_PATH), NewsLinkRegistry.class);
    }

    /** 등록표 파일을 읽는다. 모르는 필드는 허용해 파일에 주석용 키를 둘 수 있게 한다. */
    static <T> T read(Resource resource, Class<T> type) {
        ObjectMapper mapper =
                new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (InputStream in = resource.getInputStream()) {
            T value = mapper.readValue(in, type);
            if (value == null) {
                throw new IllegalStateException("등록표가 비어 있다: " + resource.getDescription());
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException("등록표를 읽을 수 없다: " + resource.getDescription(), e);
        }
    }
}
