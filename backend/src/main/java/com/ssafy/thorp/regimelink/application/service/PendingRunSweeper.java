package com.ssafy.thorp.regimelink.application.service;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 004 실행을 기다리는 링크를 다시 본다 (T033).
 *
 * <p><b>004 코드에 011 전용 이벤트를 추가하지 않는다</b>(plan 위험 표). 실행이 끝났는지는 상태를 읽어 확인하고, 끝났으면 작업을 조립해 발행한다 — 004
 * 가 011 을 알게 되면 두 spec 이 서로를 부르는 고리가 생긴다.
 *
 * <p>발행이 브로커에 닿지 못해 {@code QUEUED}로만 남은 링크도 여기서 다시 집는다({@code job_message_id}가 비어 있는 것이 그 표시다).
 *
 * <p>건마다 트랜잭션을 연다 — 하나가 실패해도 나머지가 돌아야 하고, 조립기가 자기 트랜잭션({@code REQUIRES_NEW})을 열기 때문이다.
 */
@Component
@ConditionalOnProperty(
        name = "thorp.regimelink.pending-run-sweep-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PendingRunSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingRunSweeper.class);

    private final RegimeLinkRepository links;
    private final RegimeLinkJobAssembler assembler;

    public PendingRunSweeper(RegimeLinkRepository links, RegimeLinkJobAssembler assembler) {
        this.links = links;
        this.assembler = assembler;
    }

    @Scheduled(fixedDelayString = "${thorp.regimelink.pending-run-sweep-interval}")
    public void sweep() {
        for (RegimeLink link : waiting()) {
            try {
                // 건마다 트랜잭션을 여는 것은 조립기 쪽이다. 여기서 부르면 프록시를 지나므로
                // 자기 호출로 트랜잭션이 사라지는 함정을 밟지 않는다.
                assembler.settlePending(link);
            } catch (RuntimeException e) {
                log.warn("regime link sweep failed: id={}", link.id(), e);
            }
        }
    }

    /**
     * 다시 볼 링크: 실행을 기다리는 것과, 발행이 브로커에 닿지 못해 {@code job_message_id}가 빈 채로 남은 것.
     *
     * <p>뒤엣것을 빼면 브로커가 잠깐 끊긴 사이에 접수된 링크가 영영 {@code QUEUED}로 남는다.
     */
    private List<RegimeLink> waiting() {
        List<RegimeLink> waiting = new ArrayList<>(links.findByStatus(LinkStatus.PENDING_RUN));
        links.findByStatus(LinkStatus.QUEUED).stream()
                .filter(link -> link.jobMessageId() == null)
                .forEach(waiting::add);
        return waiting;
    }
}
