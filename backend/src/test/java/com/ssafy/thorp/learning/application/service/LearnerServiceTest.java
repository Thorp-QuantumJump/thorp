package com.ssafy.thorp.learning.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.learning.application.exception.ArticleUnavailableException;
import com.ssafy.thorp.learning.application.repository.BadgeAwardRepository;
import com.ssafy.thorp.learning.application.repository.ExpLedgerRepository;
import com.ssafy.thorp.learning.application.repository.LearnerAnswerRepository;
import com.ssafy.thorp.learning.application.repository.LearnerProgressRepository;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.ExpLedger;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 나의 학습 유스케이스 가운데 DB로 재현하기 어려운 경합(learning-me-api §0). 저장소는 목이다. 계약 전체와 실제 SQL은 {@code
 * LearnerApiIT}가 본다.
 */
class LearnerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final long USER = 9301;
    private static final ArticleId ARTICLE = new ArticleId("etf-fees");

    private final LearningStageRepository stageRepository = mock(LearningStageRepository.class);
    private final LearningArticleRepository articleRepository =
            mock(LearningArticleRepository.class);
    private final PublishedTermRepository publishedTermRepository =
            mock(PublishedTermRepository.class);
    private final LearnerProgressRepository progressRepository =
            mock(LearnerProgressRepository.class);
    private final LearnerAnswerRepository answerRepository = mock(LearnerAnswerRepository.class);
    private final ExpLedgerRepository ledgerRepository = mock(ExpLedgerRepository.class);
    private final BadgeAwardRepository badgeRepository = mock(BadgeAwardRepository.class);

    private LearnerService service;

    @BeforeEach
    void setUp() {
        service =
                new LearnerService(
                        stageRepository,
                        articleRepository,
                        publishedTermRepository,
                        progressRepository,
                        answerRepository,
                        ledgerRepository,
                        badgeRepository,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(ledgerRepository.find(anyLong())).thenReturn(ExpLedger.empty());
        // 기록 가능 확인 때는 CURRENT 판이 게시 중이었다.
        when(articleRepository.findPublishedEdition(ARTICLE))
                .thenReturn(Optional.of(Edition.CURRENT));
        when(stageRepository.findAllOrdered())
                .thenReturn(
                        List.of(
                                new LearningStage(
                                        1L,
                                        new StageKey("etf-market-basics"),
                                        1,
                                        "ETF와 국내 시장 기초",
                                        "입문",
                                        "합성 설명",
                                        NOW.minusSeconds(86400),
                                        7L)));
        // 그 뒤 게시 취소가 커밋되어 공개 범위를 읽을 때는 이 글이 없다.
        when(articleRepository.findCurrentInReadingOrder()).thenReturn(List.of());
    }

    @Test
    @DisplayName("확인 직후 게시 취소된 글의 완료는 500이 아니라 404이고 아무것도 쓰지 않는다")
    void completionOfArticleUnpublishedMeanwhile() {
        assertThatThrownBy(() -> service.complete(USER, ARTICLE.value()))
                .isInstanceOf(ArticleUnavailableException.class);

        verify(progressRepository, never()).recordCompletion(anyLong(), any(), any());
        verify(ledgerRepository, never()).add(anyLong(), any());
    }

    @Test
    @DisplayName("확인 직후 게시 취소된 글의 기록 조회는 500이 아니라 404다")
    void recordOfArticleUnpublishedMeanwhile() {
        assertThatThrownBy(() -> service.article(USER, ARTICLE.value()))
                .isInstanceOf(ArticleUnavailableException.class);
    }

    @Test
    @DisplayName("완료는 무엇을 읽기 전에 사용자 잠금부터 잡는다")
    void completionLocksLearnerFirst() {
        assertThatThrownBy(() -> service.complete(USER, ARTICLE.value()))
                .isInstanceOf(ArticleUnavailableException.class);

        InOrder order = inOrder(progressRepository, articleRepository);
        order.verify(progressRepository).lockLearner(USER);
        order.verify(articleRepository).findPublishedEdition(ARTICLE);
    }
}
