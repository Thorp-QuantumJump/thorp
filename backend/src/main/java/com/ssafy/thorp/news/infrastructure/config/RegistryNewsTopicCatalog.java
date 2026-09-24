package com.ssafy.thorp.news.infrastructure.config;

import com.ssafy.thorp.news.application.port.NewsTopicCatalog;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link NewsTopicCatalog} 구현. 버전 관리되는 등록표 파일을 읽어 application 계층에 넘긴다.
 *
 * <p>파일 형식을 아는 곳은 여기뿐이다. 규칙 코드는 주제 키와 표시명, 국내 연결 여부만 본다.
 */
@Component
public class RegistryNewsTopicCatalog implements NewsTopicCatalog {

    private final NewsLinkRegistry registry;

    public RegistryNewsTopicCatalog(NewsLinkRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String version() {
        return registry.version();
    }

    @Override
    public Optional<String> resolveTopicKey(String sourceCategory) {
        return registry.resolveTopicKey(sourceCategory);
    }

    @Override
    public Optional<Topic> findTopic(String topicKey) {
        return registry.findTopic(topicKey)
                .map(
                        topic ->
                                new Topic(
                                        topic.topicKey(),
                                        topic.displayName(),
                                        topic.domesticLink(),
                                        topic.sectorIds()));
    }

    @Override
    public Set<String> domesticLinkTopicKeys() {
        return registry.domesticLinkTopicKeys();
    }

    @Override
    public java.util.Map<String, String> matchTitle(String title) {
        return registry.matchTitle(title);
    }
}
