package com.ssafy.thorp.news.application.result;

import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Instant;
import java.util.List;

/**
 * 뉴스 묶음 조회의 유스케이스 출력(contracts/openapi.yaml {@code NewsDigestResult}).
 *
 * <p><b>빈 결과도 정상 출력이다.</b> 자료가 없거나 수집이 늦었거나 매체 회신을 기다리는 중인 것은 오류가 아니라 상태다. 화면은 이 값을 그대로 보여 주고, 세
 * 묶음을 채우려고 다른 기사를 끌어오지 않는다(spec FR-001·FR-014, 원칙 VI).
 *
 * <p>기사 본문은 어떤 필드에도 없다. 원문은 링크로만 연결한다(spec FR-002).
 *
 * @param context 현재 뉴스인지 과거 구간인지.
 * @param state 표시 상태. READY·EMPTY·STALE·PREPARING 모두 HTTP 200이다.
 * @param sectorId 요청이 좁힌 섹터. 전체 조회면 null.
 * @param from 요청한 구간 시작. 현재 조회면 null.
 * @param to 요청한 구간 끝. 현재 조회면 null.
 * @param lastUpdatedAt 노출 가능한 뉴스원들의 마지막 성공 시각 중 최신값. 수집 이력이 없으면 null.
 * @param stale 마지막 갱신이 기준보다 오래됐는지. 화면이 '실시간' 표현을 쓰지 않도록 알린다(spec FR-014).
 * @param statusMessage 상태를 사람 말로 옮긴 문장. READY면 null.
 * @param digests 표시할 묶음. 최대 {@code thorp.news.max-digests}개다.
 */
public record NewsDigestListResult(
        NewsDigestQuery.Context context,
        NewsDigestState state,
        String sectorId,
        Instant from,
        Instant to,
        Instant lastUpdatedAt,
        boolean stale,
        String statusMessage,
        List<Digest> digests) {

    public NewsDigestListResult {
        digests = List.copyOf(digests);
    }

    /**
     * 묶음 하나.
     *
     * @param digestId 대표 기사와 묶음 규칙 버전에서 유도한 안정 식별자. 내부 순번이 아니다(spec FR-025).
     * @param representativeTitle 대표 기사의 제목. 생성하지 않고 그대로 쓴다.
     * @param factualSummary 제목·매체명·주제만으로 만든 한 문장.
     * @param firstPublishedAt 검증된 최초 발행 시각. 확인된 시각이 없으면 null.
     * @param publicationTimeStatus 발행 시각을 확인했는지. 화면이 '미확인'을 표시할 근거다.
     * @param publisherCount 서로 다른 매체 수. 표시 대상 기사만으로 센다.
     * @param sectors 연결된 섹터 식별자. spec 001이 소유하는 값이다.
     * @param topics 연결된 주제 키.
     * @param importanceRuleVersion 이 순서를 만든 규칙 버전(원칙 VIII).
     * @param articles 묶음에 속한 기사.
     */
    public record Digest(
            String digestId,
            String representativeTitle,
            String factualSummary,
            Instant firstPublishedAt,
            PublicationTimeStatus publicationTimeStatus,
            int publisherCount,
            List<String> sectors,
            List<String> topics,
            String importanceRuleVersion,
            List<Article> articles) {

        public Digest {
            sectors = List.copyOf(sectors);
            topics = List.copyOf(topics);
            articles = List.copyOf(articles);
        }
    }

    /**
     * 기사 하나. 본문은 없다.
     *
     * @param articleId canonical URL에서 유도한 안정 식별자.
     * @param title 원문 제목.
     * @param publisher 매체 표시명.
     * @param publishedAt 검증된 발행 시각. 확인하지 못했으면 null.
     * @param publicationTimeStatus 발행 시각 확인 여부.
     * @param originalUrl 원문 링크. 본문을 옮겨 오지 않고 여기로만 연결한다.
     */
    public record Article(
            String articleId,
            String title,
            String publisher,
            Instant publishedAt,
            PublicationTimeStatus publicationTimeStatus,
            String originalUrl) {}
}
