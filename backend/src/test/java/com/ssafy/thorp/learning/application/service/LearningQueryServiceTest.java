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
import com.ssafy.thorp.learning.application.port.PublishCheckRulesPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort.SectorMapView;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.result.CurrentArticleEntry;
import com.ssafy.thorp.learning.application.result.LearningArticleResult;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.LegacyTermBlock;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.MentionRunView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.ParagraphView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.TermView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.UnavailableReason;
import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.application.result.PublishedArticleView;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.KeySummaryBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.model.QuizChoice;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.model.ReadingEstimate;
import com.ssafy.thorp.learning.domain.model.RichText;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import com.ssafy.thorp.learning.infrastructure.rules.YamlPublishCheckRulesLoader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 공개 조회 유스케이스의 판정(learning-public-api §2·§3). 저장소·단계·대응표 포트는 목이고 규칙 파일은 실제 파일이다.
 *
 * <p>이 서비스는 초안 용어 저장소({@code LearningTermRepository})를 주입받지 않는다. 생성자에 그 타입이 없다는 것 자체가 "공개 응답을 초안
 * 용어로 만들지 않는다"는 구조적 보장이고, 아래 {@link #termSentenceComesFromSnapshot}이 값으로 확인한다.
 */
class LearningQueryServiceTest {

    private static final ArticleId ARTICLE = new ArticleId("returns-and-losses");
    private static final ArticleId CURRENT_ARTICLE = new ArticleId("volatility-is-not-loss");
    private static final TermId MAX_DRAWDOWN = new TermId("max-drawdown");
    private static final TermId VOLATILITY = new TermId("volatility");
    private static final StageKey RETURNS_AND_RISK = new StageKey("returns-and-risk");
    private static final Placement FIRST_IN_STAGE_2 = new Placement(RETURNS_AND_RISK, 1);

    private LearningArticleRepository articleRepository;
    private PublishedTermRepository publishedTermRepository;
    private LearningStageRepository stageRepository;
    private SectorMapPort sectorMapPort;
    private LearningQueryService service;

    @BeforeEach
    void setUp() {
        articleRepository = mock(LearningArticleRepository.class);
        publishedTermRepository = mock(PublishedTermRepository.class);
        stageRepository = mock(LearningStageRepository.class);
        sectorMapPort = mock(SectorMapPort.class);
        PublishCheckRulesPort rulesPort = new YamlPublishCheckRulesLoader();
        service =
                new LearningQueryService(
                        articleRepository,
                        publishedTermRepository,
                        stageRepository,
                        sectorMapPort,
                        rulesPort);
        givenStages(false);
    }

    /** 2단계(returns-and-risk)를 포함한 두 단계. {@code secondReleased}면 2단계가 공개됐다. */
    private void givenStages(boolean secondReleased) {
        when(stageRepository.findAllOrdered())
                .thenReturn(
                        List.of(
                                new LearningStage(
                                        1L,
                                        new StageKey("etf-market-basics"),
                                        1,
                                        "ETF와 국내 시장 기초",
                                        "입문",
                                        "s",
                                        secondReleased ? Instant.EPOCH : null,
                                        secondReleased ? 7L : null),
                                new LearningStage(
                                        2L,
                                        RETURNS_AND_RISK,
                                        2,
                                        "수익과 위험 읽기",
                                        "입문",
                                        "s",
                                        secondReleased ? Instant.EPOCH : null,
                                        secondReleased ? 7L : null)));
    }

    private static ArticleRevision revision(String snapshotSentence) {
        ArticleSnapshot snapshot =
                ArticleSnapshot.legacy(
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
                10L,
                1,
                Edition.LEGACY,
                snapshot,
                "learning-publish-checks-v1",
                "SEM-1",
                7L,
                Instant.EPOCH);
    }

    /** 예시 없는 V2 판. 문단에 이 글의 용어와 다른 글의 용어를 하나씩 언급한다. */
    private static ArticleRevision currentRevision() {
        List<ArticleBlock> blocks =
                List.of(
                        new KeySummaryBlock(
                                List.of(
                                        RichText.text("첫 줄"),
                                        RichText.text("둘째 줄"),
                                        RichText.text("셋째 줄"))),
                        new HeadingBlock("소제목"),
                        new ParagraphBlock(
                                new RichText(
                                        List.of(
                                                new TextRun("오르내림인 "),
                                                new TermMentionRun(VOLATILITY, "변동성"),
                                                new TextRun("과 "),
                                                new TermMentionRun(MAX_DRAWDOWN, "가장 크게 줄었던 폭")))),
                        new TermBlock(VOLATILITY));
        ArticleSnapshot snapshot =
                new ArticleSnapshot(
                        ArticleFormat.V2,
                        CURRENT_ARTICLE,
                        "변동성은 손실일까?",
                        "질문",
                        204,
                        blocks,
                        null,
                        List.of(),
                        List.of(
                                LearningTerm.v2(
                                        VOLATILITY,
                                        "변동성",
                                        List.of("volatility"),
                                        "뜻",
                                        "읽는 법",
                                        "한계")),
                        7,
                        List.of(
                                new QuizQuestion(
                                        new QuestionId("q1"),
                                        "질문",
                                        List.of(
                                                new QuizChoice(new ChoiceId("c2"), "가", false),
                                                new QuizChoice(new ChoiceId("c3"), "나", true)),
                                        "풀이")),
                        new ReadingEstimate(2480, 5, 100));
        return new ArticleRevision(
                20L,
                2,
                Edition.CURRENT,
                snapshot,
                "learning-publish-checks-v2",
                ArticleRevision.NOT_CHECKED,
                7L,
                Instant.EPOCH);
    }

    private void givenPublished(ArticleRevision revision) {
        when(articleRepository.findPublished(revision.snapshot().articleId()))
                .thenReturn(Optional.of(new PublishedArticleView(revision, FIRST_IN_STAGE_2)));
    }

    @Test
    @DisplayName("없는 글과 초안 글은 같은 ArticleUnavailableException이다")
    void missingAndDraftAreTheSame() {
        when(articleRepository.findPublished(any())).thenReturn(Optional.empty());

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
        verify(articleRepository, never()).findPublished(any());
    }

    @Test
    @DisplayName("term 블록 문장은 판의 용어에서 온다")
    void termSentenceComesFromSnapshot() {
        givenPublished(revision("판의 문장"));
        when(sectorMapPort.currentMap()).thenReturn(Optional.empty());

        LearningArticleResult result = service.getPublished("returns-and-losses");

        assertThat(result.blocks())
                .contains(new LegacyTermBlock("max-drawdown", "가장 크게 줄었던 폭", "판의 문장"));
    }

    @Test
    @DisplayName("공개된 단계가 없으면 LEGACY 판은 PRE_RELEASE, 있으면 PREVIOUS이고 기록 필드는 비어 있다")
    void legacyEditionFollowsStageRelease() {
        givenPublished(revision("s"));
        when(sectorMapPort.currentMap()).thenReturn(Optional.empty());

        LearningArticleResult beforeRelease = service.getPublished("returns-and-losses");
        givenStages(true);
        LearningArticleResult afterRelease = service.getPublished("returns-and-losses");

        assertThat(beforeRelease.edition()).isEqualTo("PRE_RELEASE");
        assertThat(beforeRelease.hasUnreleasedStages()).isTrue();
        assertThat(afterRelease.edition()).isEqualTo("PREVIOUS");
        assertThat(afterRelease.hasUnreleasedStages()).isFalse();
        assertThat(beforeRelease.stage().stageKey()).isEqualTo("returns-and-risk");
        assertThat(beforeRelease.number()).isNull();
        assertThat(beforeRelease.next()).isNull();
        assertThat(beforeRelease.stageArticleCount()).isNull();
        assertThat(beforeRelease.minDwellSeconds()).isNull();
        assertThat(beforeRelease.quiz()).isEmpty();
        // 본문 '문단'+'가장크게줄었던폭'+'s'+'가상' = 13자 → 반올림 0분 → 최소 1분
        assertThat(beforeRelease.readingMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("CURRENT 판은 번호·다음 글·단계 글 수·판의 읽기 값을 싣는다")
    void currentArticleCarriesReadingOrder() {
        givenStages(true);
        givenPublished(currentRevision());
        when(articleRepository.findCurrentInReadingOrder())
                .thenReturn(
                        List.of(
                                new CurrentArticleEntry(
                                        "sectors-and-etf", "섹터와 ETF", "etf-market-basics", 1, 1),
                                new CurrentArticleEntry(
                                        "volatility-is-not-loss",
                                        "변동성은 손실일까?",
                                        "returns-and-risk",
                                        2,
                                        4),
                                new CurrentArticleEntry(
                                        "weights-and-rebalancing",
                                        "비중과 리밸런싱",
                                        "allocation-rules",
                                        3,
                                        1)));
        when(publishedTermRepository.findPublishedAliases(Set.of(MAX_DRAWDOWN)))
                .thenReturn(Map.of(MAX_DRAWDOWN, List.of("MDD")));

        LearningArticleResult result = service.getPublished("volatility-is-not-loss");

        assertThat(result.edition()).isEqualTo("CURRENT");
        assertThat(result.number()).isEqualTo("2-1");
        assertThat(result.next())
                .isEqualTo(
                        new LearningArticleResult.NextArticle(
                                "weights-and-rebalancing", "3-1", "비중과 리밸런싱"));
        assertThat(result.stageArticleCount()).isEqualTo(1);
        assertThat(result.readingMinutes()).isEqualTo(5);
        assertThat(result.minDwellSeconds()).isEqualTo(100);
        assertThat(result.example()).isNull();
        assertThat(result.quiz())
                .singleElement()
                .satisfies(q -> assertThat(q.correctChoiceId()).isEqualTo("c3"));
    }

    @Test
    @DisplayName("언급 조각의 owned·aliases는 이 글의 용어면 판에서, 다른 글의 용어면 게시 색인에서 온다")
    void mentionsCarryOwnershipAndAliases() {
        givenStages(true);
        givenPublished(currentRevision());
        when(articleRepository.findCurrentInReadingOrder())
                .thenReturn(
                        List.of(
                                new CurrentArticleEntry(
                                        "volatility-is-not-loss",
                                        "변동성은 손실일까?",
                                        "returns-and-risk",
                                        2,
                                        4)));
        when(publishedTermRepository.findPublishedAliases(Set.of(MAX_DRAWDOWN)))
                .thenReturn(Map.of(MAX_DRAWDOWN, List.of("MDD")));

        LearningArticleResult result = service.getPublished("volatility-is-not-loss");

        ParagraphView paragraph = (ParagraphView) result.blocks().get(2);
        assertThat(paragraph.content())
                .filteredOn(MentionRunView.class::isInstance)
                .containsExactly(
                        new MentionRunView("volatility", "변동성", true, List.of("volatility")),
                        new MentionRunView("max-drawdown", "가장 크게 줄었던 폭", false, List.of("MDD")));
        assertThat(result.blocks().get(3))
                .isEqualTo(
                        new TermView(
                                "volatility", "변동성", List.of("volatility"), "뜻", "읽는 법", "한계"));
        assertThat(paragraph.plainText()).isEqualTo("오르내림인 변동성과 가장 크게 줄었던 폭");
    }

    @Test
    @DisplayName("게시되지 않은 다른 글의 용어를 언급하면 aliases가 비어 있다")
    void unpublishedForeignMentionHasNoAliases() {
        givenStages(true);
        givenPublished(currentRevision());
        when(articleRepository.findCurrentInReadingOrder())
                .thenReturn(
                        List.of(
                                new CurrentArticleEntry(
                                        "volatility-is-not-loss",
                                        "변동성은 손실일까?",
                                        "returns-and-risk",
                                        2,
                                        4)));
        when(publishedTermRepository.findPublishedAliases(any())).thenReturn(Map.of());

        ParagraphView paragraph =
                (ParagraphView) service.getPublished("volatility-is-not-loss").blocks().get(2);

        assertThat(paragraph.content())
                .filteredOn(MentionRunView.class::isInstance)
                .last()
                .isEqualTo(new MentionRunView("max-drawdown", "가장 크게 줄었던 폭", false, List.of()));
    }

    @Test
    @DisplayName("예시가 없는 V2 판은 대응표를 읽지 않고 example이 null이다")
    void currentWithoutExampleSkipsSectorMap() {
        givenStages(true);
        givenPublished(currentRevision());
        when(articleRepository.findCurrentInReadingOrder())
                .thenReturn(
                        List.of(
                                new CurrentArticleEntry(
                                        "volatility-is-not-loss",
                                        "변동성은 손실일까?",
                                        "returns-and-risk",
                                        2,
                                        4)));
        when(publishedTermRepository.findPublishedAliases(any())).thenReturn(Map.of());

        assertThat(service.getPublished("volatility-is-not-loss").example()).isNull();
        verify(sectorMapPort, never()).currentMap();
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
    @DisplayName("목록은 저장소의 항목을 그대로 돌려주고 대응표를 읽지 않는다")
    void listDoesNotReadSectorMap() {
        LearningArticleSummaryResult item =
                new LearningArticleSummaryResult("returns-and-losses", "수익과 손실", "질문", 8);
        when(articleRepository.findPublishedOrdered()).thenReturn(List.of(item));

        assertThat(service.listPublished()).containsExactly(item);
        verify(sectorMapPort, never()).currentMap();
    }

    @Test
    @DisplayName("공개 판이 다른 글의 것이면 그 본문을 내보내지 않고 실패한다")
    void rejectsRevisionOfAnotherArticle() {
        when(articleRepository.findPublished(new ArticleId("backtest")))
                .thenReturn(Optional.of(new PublishedArticleView(revision("s"), FIRST_IN_STAGE_2)));

        assertThatThrownBy(() -> service.getPublished("backtest"))
                .isInstanceOf(IllegalStateException.class);
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
