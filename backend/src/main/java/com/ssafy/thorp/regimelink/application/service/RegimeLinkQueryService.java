package com.ssafy.thorp.regimelink.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.port.BacktestRunReadPort;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkResultRepository;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkUseRepository;
import com.ssafy.thorp.regimelink.application.result.LinkDetailResult;
import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.model.RegimeLinkUse;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 링크 상태와 구간 요약 조회 (T040, contracts §3·§4).
 *
 * <p><b>공개 조회이지만 아무나 아무 링크를 열지는 못한다.</b> 링크를 돌려주는 조회는 그 링크가 붙은 분석의 공개 토큰을 함께 받고, 토큰의 요청이 그 링크와 맞지
 * 않으면 404 다(contracts §0). 응답에는 <b>제시된 토큰만</b> 싣는다 — 다른 토큰을 실으면 링크 하나로 남의 분석 주소가 새어 나간다(research
 * R14).
 */
@Service
public class RegimeLinkQueryService {

    private final RegimeLinkRepository links;
    private final RegimeLinkUseRepository uses;
    private final RegimeLinkResultRepository results;
    private final BacktestRunReadPort runs;

    public RegimeLinkQueryService(
            RegimeLinkRepository links,
            RegimeLinkUseRepository uses,
            RegimeLinkResultRepository results,
            BacktestRunReadPort runs) {
        this.links = links;
        this.uses = uses;
        this.results = results;
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public LinkDetailResult get(LinkId linkId, String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "결과 토큰이 필요합니다.");
        }
        RegimeLink link =
                links.findByLinkId(linkId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 형식(400)·존재(404)·만료(410) 판정은 005 가 소유한다. 011 이 다시 정의하지 않는다.
        BundleRunView run = runs.resolveBundle(token, null).get(0);
        if (!belongsTo(link, run)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return detail(link, token);
    }

    /**
     * 사례 하나에 붙은 링크 묶음 (contracts §4).
     *
     * <p>기준 → 비교 대상 순서이고 <b>없는 조합은 요소가 없다</b> — 빈 값을 만들어 내지 않는다. 각 요소의 {@code publicToken}은 그 요소가
     * 속한 쪽의 제시된 토큰이다.
     */
    @Transactional(readOnly = true)
    public List<LinkDetailResult> findByCase(String base, String compare, CaseRef caseRef) {
        if (base == null || base.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "기준 결과 토큰이 필요합니다.");
        }
        List<BundleRunView> bundle = runs.resolveBundle(base, compare);
        List<LinkDetailResult> found = new ArrayList<>();
        for (BundleRunView run : bundle) {
            for (RegimeLink link : linksOf(caseRef, run)) {
                found.add(detail(link, run.publicToken()));
            }
        }
        return found;
    }

    /**
     * 그 실행에서 비롯된 이 사례의 링크.
     *
     * <p>{@code CONTINUOUS}는 링크의 {@code run_id}가 곧 그 실행이지만, 현금 시작·전체 기간 재계산은 <b>다른 실행</b>을 입력으로 쓴다.
     * 그래서 이용 기록의 {@code origin_run_id}로도 찾는다 — 그 값이 "어느 전략의 결과인가"를 정한다(data-model §4a).
     */
    private List<RegimeLink> linksOf(CaseRef caseRef, BundleRunView run) {
        Map<Long, RegimeLink> byId = new LinkedHashMap<>();
        links.findByCase(caseRef, List.of(run.runId())).forEach(link -> byId.put(link.id(), link));
        for (RegimeLinkUse use : uses.findByOriginRunId(run.runId())) {
            links.findById(use.linkId())
                    .filter(link -> link.caseRef().equals(caseRef))
                    .ifPresent(link -> byId.putIfAbsent(link.id(), link));
        }
        return List.copyOf(byId.values());
    }

    /** 제시된 토큰의 실행·요청이 이 링크와 이어져 있는가. 링크의 입력 실행이거나, 이용 기록의 원래 실행·요청이면 맞다. */
    private boolean belongsTo(RegimeLink link, BundleRunView run) {
        if (Objects.equals(link.runId(), run.runId())) {
            return true;
        }
        return uses.findByLinkId(link.id()).stream()
                .anyMatch(
                        use ->
                                Objects.equals(use.originRunId(), run.runId())
                                        || (run.requestId() != null
                                                && (Objects.equals(
                                                                use.originRequestId(),
                                                                run.requestId())
                                                        || Objects.equals(
                                                                use.createdRequestId(),
                                                                run.requestId()))));
    }

    private LinkDetailResult detail(RegimeLink link, String token) {
        return new LinkDetailResult(
                link.linkId(),
                token,
                link.caseRef(),
                link.scenario(),
                link.status(),
                link.postWindow(),
                link.availability(),
                link.status() == LinkStatus.PENDING_RUN
                        ? runs.readProgress(link.runId()).orElse(null)
                        : null,
                failure(link),
                link.queuedAt(),
                link.startedAt(),
                link.finishedAt(),
                link.status() == LinkStatus.COMPLETED
                        ? results.findByLinkId(link.id()).orElse(null)
                        : null);
    }

    private static LinkDetailResult.Failure failure(RegimeLink link) {
        LinkFailure failure = link.failure();
        if (failure == null || link.status() != LinkStatus.FAILED) {
            return null;
        }
        return new LinkDetailResult.Failure(
                failure.code(), failure.message(), failure.retryable(link.attempt()));
    }
}
