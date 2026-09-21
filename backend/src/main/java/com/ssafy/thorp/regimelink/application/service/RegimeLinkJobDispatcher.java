package com.ssafy.thorp.regimelink.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 링크가 저장된 <b>뒤에</b> 작업을 발행한다 (004 {@code BacktestJobDispatcher}와 같은 이유, R-18).
 *
 * <p>접수 트랜잭션 안에서 발행하면 워커의 {@code STARTED}가 커밋을 앞질러 도착해, 이벤트 처리가 아직 보이지 않는 링크를 "모르는 링크"로 버린다.
 *
 * <p>발행에 실패해도 접수는 성공이다 — 링크는 {@code QUEUED}·{@code job_message_id}가 빈 채로 남고 {@link
 * PendingRunSweeper}가 다시 집어 든다. 여기서 예외를 올리면 이미 커밋된 접수를 되돌릴 수 없으면서 호출자에게만 오류가 간다.
 */
@Component
public class RegimeLinkJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RegimeLinkJobDispatcher.class);

    private final RegimeLinkJobAssembler assembler;

    public RegimeLinkJobDispatcher(RegimeLinkJobAssembler assembler) {
        this.assembler = assembler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQueued(RegimeLinkOpenService.RegimeLinkQueued event) {
        try {
            assembler.publish(event.linkDbId());
        } catch (RuntimeException e) {
            log.warn(
                    "regime link job dispatch failed; sweeper will retry: id={}",
                    event.linkDbId(),
                    e);
        }
    }
}
