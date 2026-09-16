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

    /**
     * 조회 설정을 application 계층이 쓰는 모양으로 옮긴다.
     *
     * <p>설정 record를 조회 서비스에 그대로 넘기지 않는다. {@code NewsProperties}는 infrastructure에 있고 수집 한도까지 함께 들고
     * 있어, application이 그것을 참조하면 계층 방향이 뒤집히고 시험이 쓰지 않는 값까지 채워야 한다.
     */
    @Bean
    public com.ssafy.thorp.news.application.service.NewsDigestQueryService.NewsQuerySettings
            newsQuerySettings(NewsProperties properties) {
        return new com.ssafy.thorp.news.application.service.NewsDigestQueryService
                .NewsQuerySettings(
                properties.exposureMode(),
                properties.staleThreshold(),
                properties.maxDigests(),
                properties.currentWindow(),
                properties.maxHistoricalRangeDays());
    }

    /**
     * 보관 기한 설정을 application 계층이 쓰는 모양으로 옮긴다(spec FR-019).
     *
     * <p>시간대 문자열은 여기서 {@link java.time.ZoneId}로 바꾼다. 이름이 틀리면 기동에서 막히는 편이 낫다 — 삭제 시점에야 알면 기한을 넘긴 뒤다.
     */
    @Bean
    public com.ssafy.thorp.news.application.service.NewsRetentionService.RetentionSettings
            newsRetentionSettings(NewsProperties properties) {
        return new com.ssafy.thorp.news.application.service.NewsRetentionService.RetentionSettings(
                properties.retentionDate(), java.time.ZoneId.of(properties.retentionZone()));
    }

    /** 등록표 파일을 읽는다. 모르는 필드는 허용해 파일에 주석용 키를 둘 수 있게 한다. */
    static <T> T read(Resource resource, Class<T> type) {
        // 시간 모듈을 넣는다. 허가 회신 시각이 ISO-8601 문자열이라 이것 없이는 읽지 못하고,
        // 그 실패가 기동 실패로 나타난다.
        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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
