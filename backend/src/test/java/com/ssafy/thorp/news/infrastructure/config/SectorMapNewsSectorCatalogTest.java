package com.ssafy.thorp.news.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.news.application.port.NewsSectorCatalog;
import com.ssafy.thorp.news.application.service.NewsSectorQueryPolicy;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 투자 대상 섹터(001)와 뉴스 전용 섹터를 합친 뉴스 섹터 목록(spec FR-005, Session 2026-09-25). */
class SectorMapNewsSectorCatalogTest {

    private final NewsLinkRegistry registry =
            NewsConfig.read(
                    new ClassPathResource(NewsConfig.LINK_REGISTRY_PATH), NewsLinkRegistry.class);

    /** 001 대응표가 아직 발행되지 않은 상태. */
    private final SectorMapNewsSectorCatalog catalog =
            new SectorMapNewsSectorCatalog(Optional::empty, registry);

    @Test
    @DisplayName("001이 발행되기 전에도 뉴스 전용 섹터는 칩으로 나오고 조회할 수 있다")
    void 뉴스_전용_섹터() {
        assertThat(catalog.selectableSectors())
                .isNotEmpty()
                .allSatisfy(
                        sector -> {
                            assertThat(sector.investable()).isFalse();
                            assertThat(sector.sectorId())
                                    .startsWith(NewsLinkRegistry.NEWS_SECTOR_PREFIX);
                        });
        assertThat(catalog.isEmpty()).as("빈 화면 판정은 001 대응표로 한다").isTrue();
        assertThat(catalog.isRegistered("NEWS_IT")).isTrue();

        NewsSectorQueryPolicy policy = new NewsSectorQueryPolicy(catalog);
        assertThat(policy.canQuery("NEWS_IT")).isTrue();
        assertThat(policy.canQuery("BANK")).as("001 섹터는 001이 발행되기 전 빈 화면이다").isFalse();
    }

    @Test
    @DisplayName("칩 목록은 등록표 순서를 따른다")
    void 순서() {
        assertThat(catalog.selectableSectors())
                .extracting(NewsSectorCatalog.SelectableSector::sectorId)
                .containsExactlyElementsOf(
                        registry.newsSectors().stream()
                                .filter(s -> s.owner() == NewsLinkRegistry.SectorOwner.NEWS)
                                .map(NewsLinkRegistry.NewsSector::sectorId)
                                .toList());
    }
}
