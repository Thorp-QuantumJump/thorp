package com.ssafy.thorp.news.application.result;

import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Instant;
import java.util.List;

/**
 * 기사 메타데이터 내보내기 결과(spec FR-017, contracts/openapi.yaml {@code NewsArticleExportEnvelope}).
 *
 * <p><b>기사 본문 필드를 두지 않는다.</b> 허가 여부와 무관하게 본문은 저장도 반환도 하지 않는다(spec FR-002).
 *
 * @param items 이 페이지의 항목.
 * @param nextCursor 다음 페이지의 자리. 더 없으면 null.
 */
public record NewsArticleExportResult(List<Item> items, String nextCursor) {

    public NewsArticleExportResult {
        items = List.copyOf(items);
    }

    /**
     * 기사 하나.
     *
     * <p><b>{@code articleId}와 {@code publicationTimeStatus} 외의 필드는 매체 회신이 허용한 경우에만 값이 있다.</b> 허용하지
     * 않은 필드는 null이고 응답에서 키가 빠진다. 빈 문자열로 채우면 "매체가 이름을 주지 않았다"와 "우리가 내보낼 권한이 없다"가 구별되지 않는다.
     *
     * <p>두 필드를 늘 담는 이유는 그것이 매체에 관한 정보가 아니기 때문이다. 식별자는 우리가 canonical URL에서 만든 값이고, 발행 시각 확인 여부는 우리가
     * 판정한 사실이다.
     *
     * @param articleId canonical URL에서 유도한 안정 식별자.
     * @param title 제목. 허용되지 않으면 null.
     * @param publisher 매체 표시명. 허용되지 않으면 null.
     * @param publishedAt 검증된 발행 시각. 허용되지 않거나 확인하지 못했으면 null.
     * @param publicationTimeStatus 발행 시각 확인 여부.
     * @param originalUrl 원문 링크. 허용되지 않으면 null.
     */
    public record Item(
            String articleId,
            String title,
            String publisher,
            Instant publishedAt,
            PublicationTimeStatus publicationTimeStatus,
            String originalUrl) {}
}
