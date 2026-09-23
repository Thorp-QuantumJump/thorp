package com.ssafy.thorp.news.application.result;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 외부 뉴스원에서 가져온 기사 한 건의 원시 메타데이터. 순수 record다.
 *
 * <p><b>본문에 해당하는 값을 담지 않는다.</b> RSS {@code description}은 매체마다 요약이거나 본문 조각이라 v1에서는 읽지 않는다. 이 계약에 그
 * 필드를 두지 않는 것으로 본문이 영속화·요약 경로에 유입되는 것을 막는다(spec FR-002, research Decision 2).
 *
 * <p>발행 시각은 뉴스원이 준 값을 파싱한 결과이며 확인 여부 판정은 도메인이 한다. timezone이 없거나 파싱할 수 없으면 null로 둔다. 수집 시각이나 GDELT
 * 기록 시각으로 대체하지 않는다(spec FR-012).
 *
 * @param sourceItemId 뉴스원 안의 불투명한 항목 식별자. RSS {@code guid} 등이며 URL로 가정하지 않는다.
 * @param url 기사 주소. redirect를 따른 최종 URL이며 없을 수 있다.
 * @param title 기사 제목.
 * @param language 언어 태그.
 * @param parsedPublishedAt 파싱에 성공한 발행 시각. 실패했거나 timezone이 없으면 null.
 * @param recordedAt GDELT 기록 시각. 발행 시각으로 쓰지 않는다.
 * @param sourceCategories 뉴스원이 준 분류 값. 등록표 매핑을 통과한 것만 연결 근거가 된다(spec FR-010).
 * @param sourceType 이 항목을 가져온 경로. 여러 경로의 결과를 합친 뒤에도 어디서 왔는지 남긴다(data-model {@code
 *     NewsArticle.sourceType}).
 */
public record CollectedNewsItem(
        String sourceItemId,
        String url,
        String title,
        String language,
        Instant parsedPublishedAt,
        Instant recordedAt,
        List<String> sourceCategories,
        com.ssafy.thorp.news.domain.vo.NewsSourceType sourceType) {

    public CollectedNewsItem {
        Objects.requireNonNull(title, "title must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title은 비어 있을 수 없다");
        }
        sourceCategories = sourceCategories == null ? List.of() : List.copyOf(sourceCategories);
        Objects.requireNonNull(sourceType, "sourceType must not be null");
    }

    /** 중복 판정에 쓸 식별 근거가 하나라도 있는지. 없으면 수집에서 제외한다(spec FR-011). */
    public boolean hasIdentity() {
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasSourceItemId = sourceItemId != null && !sourceItemId.isBlank();
        return hasUrl || hasSourceItemId || parsedPublishedAt != null;
    }
}
