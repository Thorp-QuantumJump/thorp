package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.exception.ArticleUnavailableException;
import com.ssafy.thorp.learning.application.exception.TermNotReadyException;
import com.ssafy.thorp.learning.application.port.SectorMapPort;
import com.ssafy.thorp.learning.application.port.SectorMapPort.SectorMapView;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.result.LearningArticleResult;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Block;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Example;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.ExampleSector;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.Source;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.UnavailableReason;
import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배우기 공개 조회 유스케이스(US1·US3 서버, learning-public-api).
 *
 * <p><b>공개 중인 판과 게시 용어 색인만 읽는다.</b> 초안 작업본({@code learning_article}의 본문 컬럼, {@code
 * learning_term})으로 응답을 만들지 않는다. 게시 뒤 초안을 고쳐도 공개 내용이 바뀌지 않아야 한다(SC-006).
 *
 * <p>사용자를 식별하지 않고 조회를 사용자별로 남기지 않는다(FR-012).
 */
@Service
@Transactional(readOnly = true)
public class LearningQueryService {

    private final LearningArticleRepository articleRepository;
    private final PublishedTermRepository publishedTermRepository;
    private final SectorMapPort sectorMapPort;

    public LearningQueryService(
            LearningArticleRepository articleRepository,
            PublishedTermRepository publishedTermRepository,
            SectorMapPort sectorMapPort) {
        this.articleRepository = articleRepository;
        this.publishedTermRepository = publishedTermRepository;
        this.sectorMapPort = sectorMapPort;
    }

    /**
     * 공개 중인 글. 판의 {@code readingOrder}, {@code articleId} 오름차순이다.
     *
     * @return 게시된 글이 없으면 빈 목록. 빈 목록은 정상 화면이다(spec Edge Cases)
     */
    public List<LearningArticleSummaryResult> listPublished() {
        return articleRepository.findPublishedOrdered().stream()
                .map(ArticleRevision::snapshot)
                .map(
                        snapshot ->
                                new LearningArticleSummaryResult(
                                        snapshot.articleId().value(),
                                        snapshot.title(),
                                        snapshot.question(),
                                        snapshot.readingOrder()))
                .toList();
    }

    /**
     * 공개 중인 글 하나.
     *
     * @throws ArticleUnavailableException 없는 글, 게시되지 않은 글, 형식이 틀린 식별자. 셋을 가르지 않는다
     */
    public LearningArticleResult getPublished(String articleId) {
        ArticleRevision revision =
                parseArticleId(articleId)
                        .flatMap(articleRepository::findPublishedRevision)
                        .orElseThrow(ArticleUnavailableException::new);
        ArticleSnapshot snapshot = revision.snapshot();
        return new LearningArticleResult(
                snapshot.articleId().value(),
                snapshot.title(),
                snapshot.question(),
                snapshot.readingOrder(),
                snapshot.bodyBlocks().stream().map(block -> toBlock(snapshot, block)).toList(),
                judgeExample(snapshot),
                snapshot.sources().stream()
                        .map(source -> new Source(source.label(), source.url()))
                        .toList(),
                revision.publishedAt());
    }

    /**
     * 공개 중인 용어 설명 하나. 게시 용어 색인만 읽는다.
     *
     * @throws TermNotReadyException 게시된 설명이 없거나 형식이 틀린 식별자. 둘을 가르지 않는다
     */
    public LearningTermResult getPublishedTerm(String termId) {
        return parseTermId(termId)
                .flatMap(publishedTermRepository::findPublished)
                .orElseThrow(TermNotReadyException::new);
    }

    /**
     * 블록을 응답 모양으로 옮긴다. {@code term} 블록의 이름·문장은 <b>같은 판</b>의 용어에서 채운다.
     *
     * <p>판에 없는 용어를 가리키면 실패시킨다. 게시 전 검사({@code TERM_BLOCKS_MATCH})가 막는 상태라 여기 오면 저장 데이터가 깨졌다는 뜻이고, 빈
     * 문장으로 채우면 없는 설명을 만들어 내게 된다(원칙 VI).
     */
    private static Block toBlock(ArticleSnapshot snapshot, ArticleBlock block) {
        return switch (block) {
            case HeadingBlock heading -> Block.text("heading", heading.text());
            case ParagraphBlock paragraph -> Block.text("paragraph", paragraph.text());
            case HypotheticalBlock hypothetical -> Block.text("hypothetical", hypothetical.text());
            case TermBlock termBlock -> {
                LearningTerm term =
                        snapshot.term(termBlock.termId())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "published revision of "
                                                                + snapshot.articleId()
                                                                + " has a term block without its term: "
                                                                + termBlock.termId()));
                yield Block.term(term.termId().value(), term.label(), term.sentence());
            }
        };
    }

    /**
     * 조회 시점의 열린 대응표로 예시를 판정한다(research R8). 판정 결과를 저장하지 않고 판의 예시 값도 바꾸지 않는다.
     *
     * <p>대응표는 이 조회에서 한 번만 읽는다. 판에 예시가 없으면 실패시킨다. 게시 전 검사({@code EXAMPLE_PRESENT})가 막는 상태다.
     */
    private Example judgeExample(ArticleSnapshot snapshot) {
        ExampleAllocation example = snapshot.example();
        if (example == null) {
            throw new IllegalStateException(
                    "published revision of " + snapshot.articleId() + " has no example");
        }
        Optional<SectorMapView> map = sectorMapPort.currentMap();
        if (map.isEmpty()) {
            return new Example(
                    false,
                    example.sectors().stream()
                            .map(
                                    sector ->
                                            new ExampleSector(
                                                    sector.code(), null, sector.weightTenths()))
                            .toList(),
                    List.of(),
                    UnavailableReason.SECTOR_MAP_NOT_READY);
        }
        Map<String, String> names = map.get().sectorNamesByCode();
        List<ExampleSector> sectors =
                example.sectors().stream()
                        .map(
                                sector ->
                                        new ExampleSector(
                                                sector.code(),
                                                names.get(sector.code()),
                                                sector.weightTenths()))
                        .toList();
        List<String> missing =
                example.sectors().stream()
                        .map(ExampleAllocation.SectorWeight::code)
                        .filter(code -> !names.containsKey(code))
                        .toList();
        return missing.isEmpty()
                ? new Example(true, sectors, List.of(), null)
                : new Example(false, sectors, missing, UnavailableReason.SECTOR_NOT_IN_MAP);
    }

    private static Optional<ArticleId> parseArticleId(String raw) {
        try {
            return Optional.of(new ArticleId(raw));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<TermId> parseTermId(String raw) {
        try {
            return Optional.of(new TermId(raw));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }
}
