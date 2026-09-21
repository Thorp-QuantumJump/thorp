package com.ssafy.thorp.regimelink.application.service;

import com.ssafy.thorp.regimelink.application.port.RegimeLinkResultCodec;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkErrorCode;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkEvent;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkFinishedPayload;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkStartedPayload;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkStatus;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkResultRepository;
import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워커 이벤트를 링크 상태로 반영한다 (T032).
 *
 * <p>상태 전이는 {@code QUEUED → RUNNING → COMPLETED | FAILED} 하나뿐이다. <b>종결된 링크에 늦게 온 이벤트는 기록만 하고 상태를
 * 바꾸지 않는다</b> — 재전달(at-least-once)로 같은 이벤트가 두 번 올 수 있고, 그것을 그대로 반영하면 끝난 결과가 되돌아간다. 같은 {@code
 * eventId}의 중복도 상태 전이가 멱등이라 따로 처리 이력 표를 두지 않는다(004와 같은 방식).
 *
 * <p><b>판정({@code availability})은 여기서 바꾸지 않는다.</b> 부분 구간 여부는 발행 전에 백엔드가 정해 링크에 박아 둔 값이고, 워커의 {@code
 * partial}은 그 전제대로 계산했다는 보고다. 자료가 개정돼 판정이 달라져도 이미 낸 결과의 전제는 그때의 판정이다.
 */
@Service
public class RegimeLinkEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RegimeLinkEventHandler.class);

    private final RegimeLinkRepository links;
    private final RegimeLinkResultRepository results;
    private final RegimeLinkResultCodec codec;
    private final Clock clock;

    public RegimeLinkEventHandler(
            RegimeLinkRepository links,
            RegimeLinkResultRepository results,
            RegimeLinkResultCodec codec,
            Clock clock) {
        this.links = links;
        this.results = results;
        this.codec = codec;
        this.clock = clock;
    }

    @Transactional
    public void handleStarted(RegimeLinkEvent<RegimeLinkStartedPayload> event) {
        RegimeLink link = find(event.linkId());
        if (link == null || link.status().terminal()) {
            return;
        }
        Instant startedAt =
                event.payload() == null || event.payload().startedAt() == null
                        ? Instant.now(clock)
                        : event.payload().startedAt();
        links.markRunning(link.id(), startedAt);
    }

    @Transactional
    public void handleFinished(RegimeLinkEvent<RegimeLinkFinishedPayload> event) {
        RegimeLink link = find(event.linkId());
        if (link == null || link.status().terminal()) {
            return;
        }
        RegimeLinkFinishedPayload payload = event.payload();
        Instant now = Instant.now(clock);
        if (payload == null) {
            links.markFailed(
                    link.id(), new LinkFailure(RegimeLinkErrorCode.ENGINE_ERROR.name(), null), now);
            return;
        }
        if (payload.status() == RegimeLinkStatus.COMPLETED && payload.result() != null) {
            results.save(link.id(), codec.toStored(payload.result(), now));
            links.markCompleted(link.id(), now);
            return;
        }
        links.markFailed(link.id(), failure(payload), now);
    }

    /**
     * DLQ 로 떨어진 작업 (계약 "토폴로지": {@code regime-link.jobs.dlq}는 백엔드가 소비한다).
     *
     * <p>본문을 읽지 않는다 — 여기까지 온 메시지는 워커가 소화하지 못한 것이라 본문이 온전하다는 보장이 없다. 발행기가 {@code correlation_id}에 넣어
     * 둔 linkId 만 쓴다.
     */
    @Transactional
    public void handleDeadLetter(String linkId) {
        RegimeLink link = find(linkId);
        if (link == null || link.status().terminal()) {
            return;
        }
        links.markFailed(
                link.id(),
                new LinkFailure(LinkFailure.SYSTEM_FAILURE, "작업이 처리되지 못하고 반송되었습니다."),
                Instant.now(clock));
    }

    /**
     * 워커가 {@code DATA_INSUFFICIENT}를 보내오면 {@code ENGINE_ERROR}로 적는다.
     *
     * <p>계약에 없는 코드이고, 그것을 '자료 부족'으로 바꾸면 계산 실패가 판정으로 둔갑한다 — 자료 부족은 발행 전 백엔드 판정이라 애초에 작업이 나가지 않았어야
     * 한다(research R13).
     */
    private static LinkFailure failure(RegimeLinkFinishedPayload payload) {
        RegimeLinkFinishedPayload.ErrorDetail error = payload.error();
        if (error == null || error.code() == null) {
            return new LinkFailure(RegimeLinkErrorCode.ENGINE_ERROR.name(), null);
        }
        return new LinkFailure(error.code().name(), error.message());
    }

    private RegimeLink find(String linkId) {
        Optional<RegimeLink> found;
        try {
            found = links.findByLinkId(LinkId.from(linkId));
        } catch (IllegalArgumentException malformed) {
            log.warn("regime link event with a malformed linkId: {}", linkId);
            return null;
        }
        if (found.isEmpty()) {
            // 모르는 링크는 로그만 남기고 ACK 한다 — 되돌려 봐야 같은 자리로 다시 온다.
            log.warn("regime link event for an unknown link: {}", linkId);
            return null;
        }
        return found.get();
    }
}
