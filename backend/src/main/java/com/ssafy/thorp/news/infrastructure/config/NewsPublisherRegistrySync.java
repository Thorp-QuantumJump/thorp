package com.ssafy.thorp.news.infrastructure.config;

import com.ssafy.thorp.news.application.repository.NewsPublisherPermissionHistoryRepository;
import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.model.NewsPublisherPermissionHistory;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import java.util.Optional;
import java.util.Set;
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
    private final NewsPublisherPermissionHistoryRepository permissions;

    public NewsPublisherRegistrySync(
            NewsPublisherRegistry registry,
            NewsPublisherRepository publishers,
            NewsPublisherPermissionHistoryRepository permissions) {
        this.registry = registry;
        this.publishers = publishers;
        this.permissions = permissions;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int added = 0;
        int updated = 0;
        int requests = 0;
        int responses = 0;
        for (NewsPublisherRegistry.Publisher entry : registry.publishers()) {
            Optional<NewsPublisher> existing = publishers.findByPublisherKey(entry.publisherKey());
            NewsPublisher publisher;
            if (existing.isPresent()) {
                publisher =
                        publishers.save(
                                existing.get()
                                        .withChannel(
                                                entry.displayName(),
                                                entry.language(),
                                                entry.registeredDomain(),
                                                entry.rssUrl(),
                                                entry.enabled()));
                updated++;
            } else {
                // 새 매체는 회신 전 상태로 들어온다. 등록했다는 것과 공개해도 된다는 것은 다르다(spec FR-015).
                publisher =
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
            // 요청을 먼저 넣는다. 등록표에 둘 다 적혀 있을 때 이력이 실제 순서대로 쌓인다.
            if (applyRequest(publisher, entry.permissionRequest())) {
                requests++;
            }
            if (applyPermission(publisher, entry.permission())) {
                responses++;
            }
        }
        reportPermissionProgress();
        // 주소는 남기지 않는다. 매체 키와 건수면 무엇이 반영됐는지 알 수 있다(spec FR-021).
        log.info(
                "매체 등록표를 반영했다 version={} added={} updated={} requests={} responses={}",
                registry.version(),
                added,
                updated,
                requests,
                responses);
    }

    /**
     * 등록표에 적힌 허가 요청을 이력으로 반영한다(spec FR-015).
     *
     * <p><b>요청했는데 답이 없는 것과 아예 요청하지 않은 것은 다른 상태다.</b> 이 경로가 없으면 둘이 똑같이 {@code PENDING}으로 보이고, 프로젝트가
     * 끝날 때 "우리가 요청은 했는가"에 답할 근거가 메일함에만 남는다.
     *
     * <p>회신과 같은 이유로 매체 행에 직접 쓰지 않고 이력을 통한다. 같은 요청을 두 번 넣지 않는 것도 같다 — 기동할 때마다 도는 코드라 그냥 두면 재시작 횟수만큼
     * 이력이 불어난다.
     *
     * @return 이번에 새로 반영했으면 참.
     */
    private boolean applyRequest(
            NewsPublisher publisher, NewsPublisherRegistry.PermissionRequest request) {
        if (request == null) {
            return false;
        }
        boolean alreadyRecorded =
                permissions.findByPublisherId(publisher.getId()).stream()
                        .anyMatch(
                                event ->
                                        event.eventType()
                                                        == com.ssafy.thorp.news.domain.vo
                                                                .PermissionEventType.REQUESTED
                                                && event.occurredAt().equals(request.requestedAt())
                                                && java.util.Objects.equals(
                                                        event.reference(), request.reference()));
        if (alreadyRecorded) {
            return false;
        }
        permissions.appendAndApply(
                NewsPublisherPermissionHistory.requested(
                        publisher.getId(), request.requestedAt(), request.reference()));
        return true;
    }

    /**
     * PD-15가 어디까지 왔는지 기동 로그에 남긴다.
     *
     * <p>공개 게이트가 열렸는지는 배포할 때마다 확인하게 되는 값인데, 지금은 DB를 직접 봐야만 알 수 있다. 매체 키와 상태만 남기므로 가림 규칙을 어기지
     * 않는다(spec FR-021).
     *
     * <p>여기서 실패해도 기동을 막지 않는다. 진행 상황을 못 적은 것 때문에 서비스가 안 뜨면 본말이 뒤집힌다.
     */
    private void reportPermissionProgress() {
        try {
            int allowed = 0;
            int denied = 0;
            int awaiting = 0;
            int neverRequested = 0;
            for (NewsPublisher publisher : publishers.findEnabled()) {
                switch (publisher.getPermissionStatus()) {
                    case ALLOWED -> allowed++;
                    case DENIED -> denied++;
                    default -> {
                        boolean requested =
                                permissions.findByPublisherId(publisher.getId()).stream()
                                        .anyMatch(
                                                event ->
                                                        event.eventType()
                                                                == com.ssafy.thorp.news.domain.vo
                                                                        .PermissionEventType
                                                                        .REQUESTED);
                        if (requested) {
                            awaiting++;
                        } else {
                            neverRequested++;
                        }
                    }
                }
            }
            log.info(
                    "매체 허가 진행(PD-15) allowed={} denied={} awaitingReply={} notRequested={}",
                    allowed,
                    denied,
                    awaiting,
                    neverRequested);
            if (allowed == 0) {
                log.info("허가된 매체가 없어 공개 조회는 준비 중 상태로 답한다(spec FR-016)");
            }
        } catch (RuntimeException e) {
            log.warn("매체 허가 진행 상황을 남기지 못했다");
        }
    }

    /**
     * 등록표에 적힌 회신을 이력으로 반영한다.
     *
     * <p><b>매체 행에 직접 쓰지 않고 이력을 통한다.</b> 그래야 append-only와 원자적 전이가 그대로 지켜지고, 회신을 받았다는 사실 자체가 남는다. 현재
     * 상태만 바꾸면 나중에 "왜 이 매체가 공개돼 있나"에 답할 근거가 없다(spec FR-015).
     *
     * <p><b>같은 회신을 두 번 넣지 않는다.</b> 기동할 때마다 도는 코드라 그냥 두면 재시작 횟수만큼 이력이 불어난다. 같은 시각·같은 결과의 회신이 이미 있으면
     * 건너뛴다. 회신 시각을 바꾸면 새 회신으로 보고 다시 넣는다 — 매체가 답을 바꾼 경우다.
     *
     * @return 이번에 새로 반영했으면 참.
     */
    private boolean applyPermission(
            NewsPublisher publisher, NewsPublisherRegistry.Permission permission) {
        if (permission == null) {
            return false;
        }
        boolean alreadyRecorded =
                permissions.findByPublisherId(publisher.getId()).stream()
                        .anyMatch(
                                event ->
                                        event.occurredAt().equals(permission.respondedAt())
                                                && event.permissionStatus() == permission.status()
                                                && event.allowedFields()
                                                        .equals(
                                                                Set.copyOf(
                                                                        permission.allowedFields()))
                                                && java.util.Objects.equals(
                                                        event.reference(), permission.reference()));
        if (alreadyRecorded) {
            return false;
        }
        permissions.appendAndApply(
                permission.status() == PermissionStatus.ALLOWED
                        ? NewsPublisherPermissionHistory.allowed(
                                publisher.getId(),
                                Set.copyOf(permission.allowedFields()),
                                permission.respondedAt(),
                                permission.reference())
                        : NewsPublisherPermissionHistory.denied(
                                publisher.getId(),
                                permission.respondedAt(),
                                permission.reference()));
        return true;
    }
}
