package com.ssafy.thorp.news.infrastructure.client;

import com.ssafy.thorp.news.application.port.GdeltNewsSource;
import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.application.port.RssNewsSource;
import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link NewsSourceClient} 구현. 뉴스원 종류에 맞는 클라이언트로 넘긴다.
 *
 * <p>포트는 RSS와 GDELT를 한 인터페이스로 묶어 두었고 실제 클라이언트는 종류마다 따로 있다. 이 클래스가 그 사이를 잇는다. application 계층은 클라이언트
 * 구현을 모른 채 포트만 쓴다.
 *
 * <p>GDELT는 DOC API로 먼저 받고, 배포 파일은 <b>보완</b>으로만 덧붙인다. 배포 레코드에는 발행 시각이 없어 그 자체로는 표시 후보가 되지 못하므로 DOC
 * 결과를 대체하지 않는다(plan "수집과 재시도").
 *
 * <p>배포 파일 수집이 실패해도 DOC 결과는 살린다. 보완원 하나가 회차 전체를 무르게 하지 않는다.
 */
@Component
public class CompositeNewsSourceClient implements NewsSourceClient {

    private static final Logger log = LoggerFactory.getLogger(CompositeNewsSourceClient.class);

    private final RssNewsClient rssClient;
    private final GdeltDocNewsClient gdeltDocClient;
    private final GdeltFileNewsClient gdeltFileClient;

    public CompositeNewsSourceClient(
            RssNewsClient rssClient,
            GdeltDocNewsClient gdeltDocClient,
            GdeltFileNewsClient gdeltFileClient) {
        this.rssClient = rssClient;
        this.gdeltDocClient = gdeltDocClient;
        this.gdeltFileClient = gdeltFileClient;
    }

    @Override
    public NewsFetchResult fetch(RssNewsSource source) {
        return rssClient.fetch(source);
    }

    @Override
    public NewsFetchResult fetch(GdeltNewsSource source) {
        NewsFetchResult doc = gdeltDocClient.fetch(source);

        List<CollectedNewsItem> merged = new ArrayList<>(doc.items());
        try {
            merged.addAll(gdeltFileClient.fetch(source).items());
        } catch (NewsSourceException e) {
            // 보완 실패는 DOC 결과를 무르게 하지 않는다. 분류 코드만 남긴다.
            log.warn(
                    "GDELT 배포 파일 보완에 실패했다 source={} category={}",
                    source.sourceKey(),
                    e.errorCategory());
        }

        // 결과 상한 여부는 DOC 조회가 판단한다. 보완분이 더해졌다고 상한이 풀리지 않는다.
        return doc.resultCapReached()
                ? NewsFetchResult.capped(merged)
                : NewsFetchResult.complete(merged, null, null);
    }
}
