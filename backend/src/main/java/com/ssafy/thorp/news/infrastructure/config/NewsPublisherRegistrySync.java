package com.ssafy.thorp.news.infrastructure.config;

import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 등록표 파일의 매체를 저장소에 반영한다(spec FR-009, PD-15a).
 *
 * <p>이 다리가 없으면 등록표에 매체를 적어도 아무 일이 일어나지 않는다. 수집 스케줄러는 저장소를 읽고, 등록표는 호출 허용 host를 판정하는 데만 쓰이기 때문이다. 둘을
 * 잇는 곳이 여기다.
 *
 * <p><b>무엇이 무엇의 원본인지 가른다.</b>
 *
 * <ul>
 *   <li><b>채널 정보</b>(이름·언어·등록 도메인·RSS 주소·수집 여부) — 등록표가 원본이다. 버전 관리되는 파일이라 누가 언제 바꿨는지 남는다
 *   <li><b>허가 상태</b> — 저장소가 원본이다. 매체 회신 이력에서 나온 값이라 파일이 알 수 없다
 *   <li><b>조건부 요청 값</b>({@code ETag}·{@code Last-Modified}) — 저장소가 원본이다. 수집이 쌓은 상태다
 * </ul>
 *
 * <p>그래서 이미 있는 매체는 채널 정보만 갈아 끼운다. 행 전체를 덮으면 기동할 때마다 허가 회신이 지워져 공개가 조용히 닫히고, 다음 수집은 조건부 요청을 쓰지 못해 매번
 * 전체 본문을 받는다.
 *
 * <p>등록표에서 빠진 매체는 지우지 않는다. 그 매체가 낸 기사와 묶음이 저장소에 남아 있고, 행을 지우면 연쇄 삭제로 그것들까지 사라진다. 수집을 멈추려면 등록표에서
 * {@code enabled}를 끈다.
 *
 * <p>기동할 때 한 번 돈다. 등록표는 배포로만 바뀌므로 그 뒤 다시 읽을 이유가 없다.
 *
 * <p><b>{@code ApplicationRunner}가 아니라 {@code SmartInitializingSingleton}이다.</b> 수집 스케줄러는 컨텍스트가 갱신될
 * 때 시작하고 {@code ApplicationRunner}는 그보다 뒤에 돈다. 그렇게 두면 첫 회차가 매체 목록이 빈 상태로 지나가고, 수집 간격이 15분이라 배포할 때마다
 * 15분의 빈 구간이 생긴다(2026-09-15 첫 실행에서 드러났다). 여기서 돌면 스케줄러가 시작하기 전에 행이 준비된다.
 *
 * <p>{@code @Transactional}을 걸지 않는다. 매체마다 저장이 각자의 트랜잭션이라 중간에 실패해도 앞의 것은 남고, 다음 기동이 같은 일을 다시 해도 결과가
 * 같다. 기동 단계에서 트랜잭션 경계를 넓게 잡으면 그 안에서 나는 실패가 기동 자체를 막는다.
 */
@Component
public class NewsPublisherRegistrySync implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(NewsPublisherRegistrySync.class);

    private final NewsPublisherRegistry registry;
    private final NewsPublisherRepository publishers;

    public NewsPublisherRegistrySync(
            NewsPublisherRegistry registry, NewsPublisherRepository publishers) {
        this.registry = registry;
        this.publishers = publishers;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int added = 0;
        int updated = 0;
        for (NewsPublisherRegistry.Publisher entry : registry.publishers()) {
            Optional<NewsPublisher> existing = publishers.findByPublisherKey(entry.publisherKey());
            if (existing.isPresent()) {
                publishers.save(
                        existing.get()
                                .withChannel(
                                        entry.displayName(),
                                        entry.language(),
                                        entry.registeredDomain(),
                                        entry.rssUrl(),
                                        entry.enabled()));
                updated++;
                continue;
            }
            // 새 매체는 회신 전 상태로 들어온다. 등록했다는 것과 공개해도 된다는 것은 다르다(spec FR-015).
            publishers.save(
                    NewsPublisher.register(
                            entry.publisherKey(),
                            entry.displayName(),
                            entry.language(),
                            entry.registeredDomain(),
                            entry.rssUrl(),
                            entry.enabled()));
            added++;
        }
        // 주소는 남기지 않는다. 매체 키와 건수면 무엇이 반영됐는지 알 수 있다(spec FR-021).
        log.info("매체 등록표를 반영했다 version={} added={} updated={}", registry.version(), added, updated);
    }
}
