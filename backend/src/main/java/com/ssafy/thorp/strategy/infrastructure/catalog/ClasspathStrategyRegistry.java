package com.ssafy.thorp.strategy.infrastructure.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.strategy.application.port.StrategyRegistry;
import com.ssafy.thorp.strategy.domain.model.StrategyCatalog;
import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 클래스패스의 전략 정의 파일을 읽어 카탈로그로 보관한다.
 *
 * <p>기동 시 한 번 읽고 불변 카탈로그를 들고 있는다. 핫 리로드를 두지 않는 이유는 정의가 배포 산출물이기 때문이다 — 실행 중에 정의가 바뀌면 같은 백테스트가 서버
 * 재기동 여부에 따라 다른 결과를 낸다.
 *
 * <p>깨진 정의 하나가 나머지를 막지 않는다(FR-017). 읽지 못한 파일은 <b>경고 로그를 남기고 제외</b>한 뒤 나머지를 제공한다. 식별자가 겹치는 경우에는 충돌한
 * 정의를 <b>모두</b> 제외한다 — 어느 쪽이 옳은지 카탈로그가 임의로 고르지 않는다.
 *
 * <p>정의가 하나도 없어도 기동에 성공한다. 빈 카탈로그는 "고를 전략이 없다"는 정상 상태이며, 이것을 기동 실패로 다루면 정의 파일 하나 때문에 서비스 전체가 뜨지
 * 못한다.
 */
@Component
public class ClasspathStrategyRegistry implements StrategyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClasspathStrategyRegistry.class);

    /** 배포되는 정의 파일의 위치. 계약 문서의 "정의 루트"와 같은 곳이다. */
    public static final String DEFAULT_LOCATION_PATTERN = "classpath*:strategies/*.json";

    private final StrategyCatalog catalog;

    /**
     * @param locationPattern 정의 파일 위치. 설정으로 빼 둔 이유는 테스트가 깨진 정의 묶음을 따로 가리켜야 하기 때문이다
     */
    public ClasspathStrategyRegistry(
            ObjectMapper objectMapper,
            @Value("${app.strategy.definitions-location:" + DEFAULT_LOCATION_PATTERN + "}")
                    String locationPattern) {
        this.catalog = load(objectMapper, locationPattern);
        log.info("전략 정의 {}건 등재: {}", catalog.size(), catalog.definitions().keySet());
    }

    @Override
    public StrategyCatalog catalog() {
        return catalog;
    }

    private static StrategyCatalog load(ObjectMapper objectMapper, String locationPattern) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            log.warn("전략 정의를 찾지 못했다: pattern={}, reason={}", locationPattern, e.getMessage());
            return StrategyCatalog.empty();
        }

        Map<String, List<StrategyDefinition>> byId = new LinkedHashMap<>();
        for (Resource resource : resources) {
            readDefinition(objectMapper, resource)
                    .ifPresent(
                            definition ->
                                    byId.computeIfAbsent(
                                                    definition.strategyId(),
                                                    key -> new ArrayList<>())
                                            .add(definition));
        }

        List<StrategyDefinition> accepted = new ArrayList<>();
        byId.forEach(
                (strategyId, definitions) -> {
                    if (definitions.size() > 1) {
                        log.warn(
                                "전략 식별자가 겹쳐 모두 제외한다: strategyId={}, 개수={}",
                                strategyId,
                                definitions.size());
                        return;
                    }
                    accepted.add(definitions.get(0));
                });
        return StrategyCatalog.of(accepted);
    }

    private static java.util.Optional<StrategyDefinition> readDefinition(
            ObjectMapper objectMapper, Resource resource) {
        String source =
                resource.getFilename() == null ? resource.toString() : resource.getFilename();
        try (InputStream input = resource.getInputStream()) {
            StrategyDefinitionJsonDto dto =
                    objectMapper.readValue(input, StrategyDefinitionJsonDto.class);
            StrategyDefinition definition = StrategyDefinitionJsonMapper.toDomain(dto, source);
            if (!source.equals(definition.strategyId() + ".json")) {
                // 파일명과 식별자가 다르면 정의를 찾아 고칠 때 엉뚱한 파일을 연다.
                log.warn(
                        "파일명과 strategyId 가 달라 제외한다: file={}, strategyId={}",
                        source,
                        definition.strategyId());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(definition);
        } catch (IOException | RuntimeException e) {
            log.warn("전략 정의를 읽지 못해 제외한다: file={}, reason={}", source, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
