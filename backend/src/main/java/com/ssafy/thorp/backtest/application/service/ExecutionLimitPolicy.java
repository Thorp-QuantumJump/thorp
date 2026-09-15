package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.ExecutionLimits;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 접수 시점의 대기열 한도(spec 004 FR-006, research R-19).
 *
 * <p>"상한을 정하기 전에 무제한 실행을 허용하지 않는다"를 지키는 자리다. 값 자체는 PD-06 이 실측 뒤 정하고 여기서는 <b>넘었는지만</b> 본다.
 *
 * <p><b>사용자별 한도는 단건({@code SINGLE})만 센다.</b> 탐색 묶음의 적재량은 007 의 발행 창({@code
 * thorp.param-search.dispatch-window})이 이미 정확히 통제한다 — 같은 큐를 두 곳에서 막으면, 나중에 창을 키운 날 탐색이 접수에서 거절당하고 그
 * 묶음이 조용히 멈춘다. 전체 한도는 반대로 종류를 가리지 않는다. 그쪽은 사용자 사이의 공정성이 아니라 서버 보호이고, 탐색도 서버를 쓰기 때문이다.
 *
 * <p><b>합류·재사용은 세지 않는다.</b> 새 계산을 만들지 않으므로 큐에 아무것도 더하지 않는다 — 한도는 "큐에 쌓이는 양"에 대한 것이다.
 *
 * <p><b>세는 구간을 자문 잠금으로 직렬화한다.</b> 잠그지 않으면 동시에 들어온 요청이 모두 같은 수를 읽고 다 같이 통과한다. 잠금은 사용자 키와 전체 키 둘이고
 * <b>항상 그 순서로</b> 잡는다 — 순서가 엇갈리면 서로 다른 사용자의 접수가 교착한다.
 *
 * <p><b>전체 한도는 모두 세되 거절은 단건에만 한다.</b> 007 은 완료 이벤트({@code BacktestFinished})로만 다음 후보를 내보내므로, 마지막 진행
 * 후보가 끝난 순간 다음 접수가 거절당하면 남은 후보가 {@code PENDING} 인 채로 <b>깨울 신호가 사라진다</b> — 묶음이 영구히 멈춘다. 접수가 거절할 수 있는
 * 시점이 하필 그 순간이다. 서버 보호의 요점은 사람이 기다리는 단건을 그만 받는 것이고, 묶음이 큐에 쌓는 양은 007 의 발행 창이 이미 4건으로 묶고 있다. 묶음도
 * <b>세기는 세므로</b> 탐색이 서버를 채우면 단건이 먼저 막힌다.
 *
 * <p>남는 위험은 동시에 도는 묶음 수를 004 가 제한하지 않는다는 것이다(묶음 수 × 창). 007 이 남은 {@code PENDING} 후보를 다시 집는 재시도 장치를
 * 두면 그때 묶음도 거절 대상에 넣어 이 예외를 거둘 수 있다.
 *
 * <p><b>여기서 던지는 429 는 호출자의 트랜잭션을 되돌리지 않는다.</b> {@link BacktestService#create}가 {@code noRollbackFor
 * = BusinessException} 이다 — 007 은 후보 정산과 다음 발행을 한 트랜잭션에서 하므로, 기본 롤백 규칙이면 접수를 거절하려다 <b>이미 끝난 후보의 완료
 * 처리까지 지우게 된다.</b> 그 설정이 안전한 근거는 이 검사가 어떤 쓰기보다도 먼저 일어난다는 것이다.
 *
 * <p>그 설정만으로는 부족했다. 완료 후보의 정산은 지켜졌지만 마지막 후보가 끝난 뒤의 거절이 묶음을 세우는 것은 그대로였다 — 그래서 위처럼 묶음을 거절하지 않는다.
 *
 * <p>이 한도는 접수만 막는다. 동시 <b>실행</b> 수는 여기서 보지 않는다(R-19) — 접수 시점에 실행 중인 것이 없으면 모두 통과한 뒤 워커가 동시에 소비하므로
 * 접수 검사로는 보장되지 않는다. 실제 상한은 워커 replica × prefetch(1)이고 그 배치 값이 곧 상한이다.
 */
@Component
public class ExecutionLimitPolicy {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLimitPolicy.class);

    /** 전체 대기열을 세는 동안 잡는 잠금. 사용자 키와 달리 하나뿐이라 상수다. */
    private static final String TOTAL_LOCK = "backtest-admission:total";

    private final BacktestRunRepository runRepository;
    private final ExecutionLimits limits;

    public ExecutionLimitPolicy(BacktestRunRepository runRepository, ExecutionLimits limits) {
        this.runRepository = runRepository;
        this.limits = limits;
    }

    /**
     * 새 실행을 만들어도 되는지 본다. 넘었으면 {@code 429 EXECUTION_LIMIT} 를 던진다.
     *
     * <p>호출자는 <b>새 실행을 만들기로 정한 뒤에만</b> 부른다 — 합류·재사용으로 끝나는 요청은 큐를 늘리지 않으므로 세지 않는다.
     */
    public void requireAdmission(long requesterId, ExecutionType executionType) {
        if (executionType == ExecutionType.SINGLE) {
            runRepository.lockAdmission(userLock(requesterId));
            long queued = runRepository.countQueuedSingleByRequester(requesterId);
            if (queued >= limits.maxQueuedPerUser()) {
                throw rejected("PER_USER", queued, limits.maxQueuedPerUser(), requesterId);
            }
        }
        runRepository.lockAdmission(TOTAL_LOCK);
        long total = runRepository.countQueued();
        if (total < limits.maxQueuedTotal()) {
            return;
        }
        if (executionType != ExecutionType.SINGLE) {
            // 세기는 세되 거절하지 않는다. 돌던 묶음을 여기서 끊으면 다시 깨어나지 못한다 — 007 은
            // 완료 이벤트로만 다음 후보를 내보내는데, 마지막 진행 후보가 끝난 뒤 거절당하면 남은
            // 후보가 PENDING 인 채로 깨울 신호가 없어진다.
            log.warn(
                    "queue over the total limit but admitting a non-single run: type={} queued={}"
                            + " limit={} requesterId={}",
                    executionType,
                    total,
                    limits.maxQueuedTotal(),
                    requesterId);
            return;
        }
        throw rejected("TOTAL", total, limits.maxQueuedTotal(), requesterId);
    }

    private static String userLock(long requesterId) {
        return "backtest-admission:user:" + requesterId;
    }

    /**
     * 재시도 시점은 큐가 얼마나 빠지는지에 달렸고 그것은 워커 수와 실행 시간에 달렸다. 추정하면 틀린 숫자를 확신 있게 주게 되므로 실행 시간 상한을 그대로 준다 —
     * <b>보장이 아니라 눈금</b>이다. 앞선 실행이 상한에 걸려 멈추더라도 스위퍼 주기와 취소 유예가 더 붙고, 멈춘 워커는 DB 를 종결로 바꿔도 브로커의 메시지를
     * 계속 쥐고 있을 수 있다. 화면은 이 값을 "이만큼 뒤에 다시 눌러 보라"로 쓰고, 그때도 429 면 다시 기다린다.
     */
    private BusinessException rejected(String reason, long queued, int limit, long requesterId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("queued", queued);
        details.put("limit", limit);
        details.put("retryAfterSeconds", limits.maxRun().toSeconds());
        if (limits.provisional()) {
            // 값이 아직 실측으로 확정되지 않았다(PD-06). 확정 정책으로 오해하지 않게 그대로 알린다.
            details.put("provisional", true);
        }
        log.info(
                "admission rejected: reason={} queued={} limit={} requesterId={}",
                reason,
                queued,
                limit,
                requesterId);
        return new BusinessException(
                ErrorCode.EXECUTION_LIMIT, ErrorCode.EXECUTION_LIMIT.getMessage(), details);
    }
}
