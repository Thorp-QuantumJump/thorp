package com.ssafy.thorp.regimelink.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.port.BacktestAcceptancePort;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkUseRepository;
import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkUse;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실패한 링크를 다시 시도한다 (T069, contracts §5).
 *
 * <p><b>자동으로 다시 실행하지 않는다</b>(Edge Cases). 실패한 계산이 저절로 되풀이되면 같은 실패가 자원을 계속 먹고, 사용자는 무엇이 진행 중인지 알 수
 * 없다.
 *
 * <p>{@code retryable=false}인 링크는 {@code 409 NOT_RETRYABLE}이다 — 같은 입력으로 다시 보내도 결과가 같은 실패이며, 그때는 화면에
 * 버튼도 없다.
 */
@Service
public class RegimeLinkRetryService {

    private final RegimeLinkRepository links;
    private final RegimeLinkUseRepository uses;
    private final BacktestAcceptancePort acceptance;
    private final ApplicationEventPublisher events;

    public RegimeLinkRetryService(
            RegimeLinkRepository links,
            RegimeLinkUseRepository uses,
            BacktestAcceptancePort acceptance,
            ApplicationEventPublisher events) {
        this.links = links;
        this.uses = uses;
        this.acceptance = acceptance;
        this.events = events;
    }

    /**
     * 004 접수가 {@code BusinessException}(대기열 상한 429 등)으로 거절할 수 있어 롤백 규칙을 푼다 — 두지 않으면 정상 거절이 500 이
     * 된다(backend/CLAUDE.md).
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public RetriedLink retry(LinkId linkId, long requesterId) {
        RegimeLink link =
                links.findByLinkId(linkId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        LinkFailure failure = link.failure();
        if (link.status() != LinkStatus.FAILED
                || failure == null
                || !failure.retryable(link.attempt())) {
            throw new BusinessException(ErrorCode.NOT_RETRYABLE);
        }

        if (LinkFailure.RUN_FAILED.equals(failure.code())) {
            // 계산이 아니라 입력 실행이 끝나지 못한 것이다. 004 재시도로 새 실행을 만들고 기다린다.
            BacktestAcceptancePort.AcceptedRun accepted =
                    acceptance.retry(link.runId(), createdRequestId(link), requesterId);
            links.markPendingRun(link.id(), accepted.runId());
            if (accepted.completed()) {
                events.publishEvent(new RegimeLinkOpenService.RegimeLinkQueued(link.id()));
                return new RetriedLink(link.linkId(), LinkStatus.QUEUED);
            }
            return new RetriedLink(link.linkId(), LinkStatus.PENDING_RUN);
        }

        // 같은 입력으로 다시 발행한다. 먼저 종결 상태를 풀어 준다 — 조립기는 끝난 링크를 건드리지
        // 않으므로, 실패 기록을 남겨 둔 채로는 재발행이 조용히 아무 일도 하지 않는다.
        // attempt 가 올라 message_id 가 달라지므로 DLQ 집계가 두 시도를 가릴 수 있다.
        links.markPendingRun(link.id(), link.runId());
        events.publishEvent(new RegimeLinkOpenService.RegimeLinkQueued(link.id()));
        return new RetriedLink(link.linkId(), LinkStatus.QUEUED);
    }

    /** 이 링크를 만든 011 요청. 없으면 004 재시도를 걸 대상이 없다. */
    private Long createdRequestId(RegimeLink link) {
        Optional<Long> found =
                uses.findByLinkId(link.id()).stream()
                        .map(RegimeLinkUse::createdRequestId)
                        .filter(java.util.Objects::nonNull)
                        .findFirst();
        return found.orElseThrow(() -> new BusinessException(ErrorCode.NOT_RETRYABLE));
    }

    public record RetriedLink(LinkId linkId, LinkStatus status) {}
}
