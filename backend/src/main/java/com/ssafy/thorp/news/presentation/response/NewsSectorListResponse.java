package com.ssafy.thorp.news.presentation.response;

import com.ssafy.thorp.news.application.port.NewsSectorCatalog;
import java.util.List;

/**
 * {@code GET /api/market-news/sectors} 응답.
 *
 * @param sectors 화면 순서의 뉴스 섹터. {@code investable}이 거짓이면 투자 대상과 대응하지 않는 뉴스 전용 섹터다.
 */
public record NewsSectorListResponse(List<Item> sectors) {

    public record Item(String sectorId, String name, boolean investable) {}

    public static NewsSectorListResponse from(List<NewsSectorCatalog.SelectableSector> sectors) {
        return new NewsSectorListResponse(
                sectors.stream()
                        .map(s -> new Item(s.sectorId(), s.name(), s.investable()))
                        .toList());
    }
}
