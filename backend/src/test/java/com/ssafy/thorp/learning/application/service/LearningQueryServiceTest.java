package com.ssafy.thorp.learning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.learning.application.exception.ArticleUnavailableException;
import com.ssafy.thorp.learning.application.exception.TermNotReadyException;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort.SectorMapView;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.result.LearningArticleResult;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Block;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.UnavailableReason;
import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 공개 조회 유스케이스의 판정(learning-public-api §2·§3). 저장소와 대응표 포트는 목이다.
 *
 * <p>이 서비스는 초안 용어 저장소({@code LearningTermRepository})를 주입받지 않는다. 생성자에 그 타입이 없다는 것 자체가 "공개 응답을 초안
 * 용어로 만들지 않는다"는 구조적 보장이고, 아래 {@link #termSentenceComesFromSnapshot}이 값으로 확인한다.
 */
class LearningQueryServiceTest {

    private static final ArticleId ARTICLE = new ArticleId("returns-and-losses");
    private static final TermId MAX_DRAWDOWN = new TermId("max-drawdown");

    private LearningArticleRepository articleRepository;
    private PublishedTermRepository publishedTermRepository;
    private SectorMapPort sectorMapPort;
    private LearningQueryService service;

    @BeforeEach
    void setUp() {
        articleRepository = mock(LearningArticleRepository.class);
        publishedTermRepository = mock(PublishedTermRepository.class);
        sectorMapPort = mock(SectorMapPort.class);
        service =
                new LearningQueryService(articleRepository, publishedTermRepository, sectorMapPort);
    }

    private static ArticleRevision revision(String snapshotSentence) {
        ArticleSnapshot snapshot =
                new ArticleSnapshot(
                        ARTICLE,
                        "수익과 손실",
                        "질문",
                        8,
                        List.of(
                                new ParagraphBlock("문단"),
                                new TermBlock(MAX_DRAWDOWN),
                                new HypotheticalBlock("가상")),
                        new ExampleAllocation(
                                List.of(
                                        new SectorWeight("SEMICONDUCTOR", 500),
                                        new SectorWeight("BATTERY", 500))),
                        List.of(),
                        List.of(new LearningTerm(MAX_DRAWDOWN, "가장 크게 줄었던 폭", snapshotSentence)),
                        3);
        return new ArticleRevision(
                10L, 1, snapshot, "learning-publish-checks-v1", "SEM-1", 7L, Instant.EPOCH);
    }

    private void givenPublished(ArticleRevision revision) {
        when(articleRepository.findPublishedRevision(ARTICLE)).thenReturn(Optional.of(revision));
    }

    @Test
    @DisplayName("없는 글과 초안 글은 같은 ArticleUnavailableException이다")
    void missingAndDraftAreTheSame() {
        when(articleRepository.findPublishedRevision(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublished("returns-and-losses"))
                .isInstanceOf(ArticleUnavailableException.class);
        assertThatThrownBy(() -> service.getPublished("no-such-article"))
                .isInstanceOf(ArticleUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bad_Key", "ab", "../etc"})
    @DisplayName("형식이 틀린 식별자도 같은 예외이고 저장소를 부르지 않는다")
    void invalidFormatIsUnavailable(String raw) {
        assertThatThrownBy(() -> service.getPublished(raw))
                .isInstanceOf(ArticleUnavailableException.class);
        verify(articleRepository, never()).findPublishedRevision(any());
    }

    @Test
    @DisplayName("term 블록 문장은 판의 용어에서 온다")
    void termSentenceComesFromSnapshot() {
        givenPublished(revision("판의 문장"));
        when(sectorMapPort.currentMap()).thenReturn(Optional.empty());

        LearningArticleResult result = service.getPublished("returns-and-losses");

        assertThat(result.blocks()).contains(Block.term("max-drawdown", "가장 크게 줄었던 폭", "판의 문장"));
    }

    @Test
    @DisplayName("대응표에 예시 섹터가 모두 있으면 available=true와 대응표의 이름이다")
    void exampleAvailableWhenAllSectorsInMap() {
        givenPublished(revision("s"));
        when(sectorMapPort.currentMap())
                .thenReturn(
                        Optional.of(
                                new SectorMapView(
                                        "SEM-1",
                                        Map.of("SEMICONDUCTOR", "반도체", "BATTERY", "2차전지"))));

        LearningArticleResult.Example example =
                service.getPublished("returns-and-losses").example();

        assertThat(example.available()).isTrue();
        assertThat(example.unavailableReason()).isNull();
        assertThat(example.missingSectorCodes()).isEmpty();
        assertThat(example.sectors())
                .extracting(LearningArticleResult.ExampleSector::name)
                .containsExactly("반도체", "2차전지");
        assertThat(example.sectors())
                .extracting(LearningArticleResult.ExampleSector::weightTenths)
                .containsExactly(500, 500);
    }

    @Test
    @DisplayName("대응표에 없는 코드가 있으면 SECTOR_NOT_IN_MAP과 빠진 코드다")
    void exampleUnavailableWhenSectorMissing() {
        givenPublished(revision("s"));
        when(sectorMapPort.currentMap())
                .thenReturn(
                        Optional.of(new SectorMapView("SEM-1", Map.of("SEMICONDUCTOR", "반도체"))));

        LearningArticleResult.Example example =
                service.getPublished("returns-and-losses").example();

        assertThat(example.available()).isFalse();
        assertThat(example.unavailableReason()).isEqualTo(UnavailableReason.SECTOR_NOT_IN_MAP);
        assertThat(example.missingSectorCodes()).containsExactly("BATTERY");
        assertThat(example.sectors())
                .extracting(LearningArticleResult.ExampleSector::name)
                .containsExactly("반도체", null);
    }

    @Test
    @DisplayName("대응표가 없으면 SECTOR_MAP_NOT_READY이고 이름이 모두 null이다")
    void exampleUnavailableWhenMapNotReady() {
        givenPublished(revision("s"));
        when(sectorMapPort.currentMap()).thenReturn(Optional.empty());

        LearningArticleResult.Example example =
                service.getPublished("returns-and-losses").example();

        assertThat(example.available()).isFalse();
        assertThat(example.unavailableReason()).isEqualTo(UnavailableReason.SECTOR_MAP_NOT_READY);
        assertThat(example.sectors())
                .extracting(LearningArticleResult.ExampleSector::name)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("대응표는 단건 조회당 한 번만 읽는다")
    void readsSectorMapOncePerArticle() {
        givenPublished(revision("s"));
        when(sectorMapPort.currentMap()).thenReturn(Optional.empty());

        service.getPublished("returns-and-losses");

        verify(sectorMapPort, times(1)).currentMap();
    }

    @Test
    @DisplayName("목록은 대응표를 읽지 않는다")
    void listDoesNotReadSectorMap() {
        when(articleRepository.findPublishedOrdered()).thenReturn(List.of(revision("s")));

        assertThat(service.listPublished())
                .singleElement()
                .satisfies(item -> assertThat(item.articleId()).isEqualTo("returns-and-losses"));
        verify(sectorMapPort, never()).currentMap();
    }

    @Test
    @DisplayName("게시된 용어가 없거나 식별자 형식이 틀리면 TermNotReadyException이다")
    void termNotReady() {
        when(publishedTermRepository.findPublished(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublishedTerm("rebalancing"))
                .isInstanceOf(TermNotReadyException.class);
        assertThatThrownBy(() -> service.getPublishedTerm("Bad_Term"))
                .isInstanceOf(TermNotReadyException.class);
    }

    @Test
    @DisplayName("게시된 용어는 색인 값 그대로다")
    void publishedTermIsReturnedAsIs() {
        LearningTermResult term =
                new LearningTermResult(
                        "max-drawdown", "가장 크게 줄었던 폭", "문장", "returns-and-losses", "수익과 손실");
        when(publishedTermRepository.findPublished(MAX_DRAWDOWN)).thenReturn(Optional.of(term));

        assertThat(service.getPublishedTerm("max-drawdown")).isEqualTo(term);
    }
}
