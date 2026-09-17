package com.ssafy.thorp.result.application.service;

import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestReuseEventRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.result.application.repository.ExpiredPublicTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 유효 시간이 지난 <b>비로그인</b> 요청을 지운다(spec 005 FR-021, T022).
 *
 * <p><b>요청 행만 지운다.</b> 실행({@code backtest_run})과 결과는 같은 조건의 다른 요청이 함께 가리킬 수 있다(004 FR-003) — 참조하는
 * 요청이 없는 실행의 판정과 삭제는 004 소유다(contracts/result-api.md "004에 요청하는 변경"). 행을 지우면 그 토큰은 404가 된다. 만료 직후
 * 정리 전까지는 {@link PublicResultService#resolve}가 410으로 막는다.
 *
 * <p><b>지우면서 묘비를 남긴다</b>(T087). 토큰 해시와 만료 시각을 {@code expired_public_token}에 같은 트랜잭션으로 적어, 지운 뒤에 들어온
 * 주소도 410으로 안내된다. 묘비는 보관 기한({@code thorp.result.tombstone-retention})이 지나면 같은 주기에 지운다.
 *
 * <p>한 건을 지우는 순서는 외래 키가 정한다: 재사용 기록 → 재시도 요청의 원본 참조 → 요청 행. <b>건마다 트랜잭션을 연다</b>({@code
 * RunLivenessSweeper}와 같은 방식) — 한 건이 실패해도(그 사이 누가 재시도해 참조가 새로 생긴 경우 등) 나머지는 지워지고, 실패한 건은 다음 주기에 다시
 * 대상이 된다.
 *
 * <p>로그에는 건수만 남긴다. 토큰은 결과를 여는 열쇠라 로그에 적지 않는다.
 *
 * <p>설정은 {@code ResultProperties}를 주입받지 않고 키로 읽는다 — 그 클래스는 {@code infrastructure}에 있어 {@code
 * application}이 참조하면 계층 규칙에 걸린다. 값이 비면 {@code ResultProperties} 검증이 기동을 막는다.
 */
@Component
@ConditionalOnProperty(
        name = "thorp.result.expiry-sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PublicResultExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(PublicResultExpirySweeper.class);

    private final BacktestRequestRepository requestRepository;
    private final BacktestReuseEventRepository reuseEventRepository;
    private final ExpiredPublicTokenRepository expiredTokens;
    private final Clock clock;
    private final int batchSize;
    private final Duration tombstoneRetention;
    private final TransactionTemplate newTransaction;

    public PublicResultExpirySweeper(
            BacktestRequestRepository requestRepository,
            BacktestReuseEventRepository reuseEventRepository,
            ExpiredPublicTokenRepository expiredTokens,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${thorp.result.expiry-sweep-batch-size}") int batchSize,
            @Value("${thorp.result.tombstone-retention}") Duration tombstoneRetention) {
        this.requestRepository = requestRepository;
        this.reuseEventRepository = reuseEventRepository;
        this.expiredTokens = expiredTokens;
        this.clock = clock;
        this.batchSize = batchSize;
        this.tombstoneRetention = tombstoneRetention;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 한 주기에 배치 크기만큼만 지운다. 밀린 양은 다음 주기가 이어받는다 — 한 번에 다 지우려다 긴 트랜잭션이 사용자 요청과 겹치지 않게 한다. */
    @Scheduled(fixedDelayString = "${thorp.result.expiry-sweep-interval}")
    public void sweep() {
        Instant now = clock.instant();
        List<BacktestRequest> expired = requestRepository.findExpiredAnonymous(now, batchSize);
        int deleted = 0;
        int failed = 0;
        for (BacktestRequest request : expired) {
            try {
                newTransaction.executeWithoutResult(status -> deleteOne(request, now));
                deleted++;
            } catch (RuntimeException exc) {
                failed++;
                log.warn(
                        "만료된 비로그인 요청 하나를 지우지 못했다. 다음 주기에 다시 시도한다: cause={}",
                        exc.getClass().getSimpleName());
            }
        }
        if (deleted > 0 || failed > 0) {
            log.info("만료된 비로그인 요청을 정리했다: deleted={} failed={}", deleted, failed);
        }
        pruneTombstones(now);
    }

    /** 요청을 지우기 전에 묘비부터 남긴다. 같은 트랜잭션이라 묘비 없이 요청만 사라지는 순간이 없다. */
    private void deleteOne(BacktestRequest request, Instant now) {
        expiredTokens.record(
                PublicTokenDigest.of(request.getPublicToken()),
                request.getPublicTokenExpiresAt(),
                now);
        long requestId = request.getId();
        reuseEventRepository.deleteByRequestId(requestId);
        requestRepository.clearRetryOf(requestId);
        requestRepository.deleteById(requestId);
    }

    /** 보관 기한이 지난 묘비를 배치 크기만큼 지운다. 실패해도 다음 주기가 이어받는다. */
    private void pruneTombstones(Instant now) {
        try {
            Integer pruned =
                    newTransaction.execute(
                            status ->
                                    expiredTokens.deleteExpiredBefore(
                                            now.minus(tombstoneRetention), batchSize));
            if (pruned != null && pruned > 0) {
                log.info("보관 기한이 지난 공개 토큰 묘비를 지웠다: deleted={}", pruned);
            }
        } catch (RuntimeException exc) {
            log.warn("공개 토큰 묘비를 지우지 못했다. 다음 주기에 다시 시도한다: cause={}", exc.getClass().getSimpleName());
        }
    }
}
