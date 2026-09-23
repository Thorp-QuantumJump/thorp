package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.application.command.FindCandidatesQuery;
import com.ssafy.thorp.paramsearch.application.port.CandidatePerformance;
import com.ssafy.thorp.paramsearch.application.port.CandidatePerformancePort;
import com.ssafy.thorp.paramsearch.application.port.CandidateRunView;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.CandidatePageResult;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.vo.CandidateId;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후보 목록 조회(FR-018, contracts/param-search-api.md §4).
 *
 * <p><b>묶음 전체를 메모리에서 거르고 정렬한다.</b> SQL 정렬을 쓰지 않는 이유가 둘이다 — 정렬 대상인 수익·하락 폭·거래비용이 004의 {@code jsonb}
 * 안에 있고 안정성은 007의 {@code jsonb} 안에 있어 한 쿼리로 줄 세우려면 양쪽을 텍스트로 캐스팅해야 하는데, 그러면 {@code "0.9" > "0.10"}
 * 처럼 문자열 순서로 어긋난다. 그리고 후보 수는 <b>후보 수 상한으로 묶여 있다</b>(FR-005b) — 상한을 넘기면 접수 자체가 거부되므로 한 묶음이 메모리에서 감당
 * 못 할 크기가 되지 않는다.
 *
 * <p>상한이 크게 올라가면 이 판단을 다시 본다. 그때는 성과를 후보 행에 비정규화하거나 {@code numeric} 캐스팅 인덱스를 두는 쪽이 된다.
 */
@Service
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class CandidateQueryService {

    private final ParamSearchRepository paramSearchRepository;
    private final SearchCandidateRepository candidateRepository;
    private final CandidatePerformancePort performancePort;

    public CandidateQueryService(
            ParamSearchRepository paramSearchRepository,
            SearchCandidateRepository candidateRepository,
            CandidatePerformancePort performancePort) {
        this.paramSearchRepository = paramSearchRepository;
        this.candidateRepository = candidateRepository;
        this.performancePort = performancePort;
    }

    @Transactional(readOnly = true)
    public CandidatePageResult find(String searchId, FindCandidatesQuery query) {
        ParamSearch search =
                paramSearchRepository
                        .findBySearchId(searchId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "탐색 묶음을 찾을 수 없습니다: " + searchId));
        List<SearchCandidate> candidates = candidateRepository.findByParamSearchId(search.getId());
        Map<Long, CandidateRunView> viewByRunId = runViews(candidates);

        List<Row> rows = new ArrayList<>();
        for (SearchCandidate candidate : candidates) {
            if (!matches(candidate, query)) {
                continue;
            }
            rows.add(new Row(candidate, viewByRunId.get(candidate.getBacktestJobId())));
        }
        rows.sort(comparatorOf(query));

        int from = Math.min(query.page() * query.size(), rows.size());
        int to = Math.min(from + query.size(), rows.size());
        List<CandidatePageResult.Item> content = new ArrayList<>();
        for (Row row : rows.subList(from, to)) {
            content.add(toItem(search, row));
        }
        return new CandidatePageResult(
                query.page(),
                query.size(),
                rows.size(),
                (int) Math.ceil((double) rows.size() / query.size()),
                content);
    }

    private Map<Long, CandidateRunView> runViews(List<SearchCandidate> candidates) {
        List<Long> runIds = new ArrayList<>();
        for (SearchCandidate candidate : candidates) {
            if (candidate.getBacktestJobId() != null) {
                runIds.add(candidate.getBacktestJobId());
            }
        }
        return performancePort.findRunViews(runIds);
    }

    private static boolean matches(SearchCandidate candidate, FindCandidatesQuery query) {
        if (query.status() != null && candidate.getStatus() != query.status()) {
            return false;
        }
        return query.selected() == null || candidate.isSelected() == query.selected();
    }

    /**
     * 정렬 비교자. <b>값이 없는 후보는 언제나 뒤로 보낸다</b> — 방향이 어느 쪽이든 계산되지 않은 후보가 목록 맨 위에 오르면 "가장 좋은 후보"로 읽힌다.
     *
     * <p>같은 값끼리는 순번으로 가른다. 표시 순서를 정해야 하지만 지표에 근거가 없을 때, 접수 순서가 유일하게 재현 가능한 기준이다(원칙 VIII).
     */
    private static Comparator<Row> comparatorOf(FindCandidatesQuery query) {
        if (query.sort() == FindCandidatesQuery.Sort.CANDIDATE_NO) {
            Comparator<Row> byNo = Comparator.comparingInt(row -> row.candidate().getCandidateNo());
            return query.descending() ? byNo.reversed() : byNo;
        }
        Comparator<BigDecimal> values =
                query.descending()
                        ? Comparator.<BigDecimal>naturalOrder().reversed()
                        : Comparator.naturalOrder();
        return Comparator.comparing(
                        (Row row) -> metricOf(row, query.sort()), Comparator.nullsLast(values))
                .thenComparingInt(row -> row.candidate().getCandidateNo());
    }

    private static BigDecimal metricOf(Row row, FindCandidatesQuery.Sort sort) {
        if (sort == FindCandidatesQuery.Sort.STABILITY) {
            StabilityScore score = StabilityScore.fromMap(row.candidate().getStability());
            return score.calculable() ? score.value() : null;
        }
        CandidatePerformance performance = row.performance();
        if (performance == null) {
            return null;
        }
        return switch (sort) {
            case NET_RETURN -> performance.netReturn();
            case MAX_DRAWDOWN -> performance.maxDrawdown();
            case TRADING_COST -> performance.tradingCost();
            default -> null;
        };
    }

    private static CandidatePageResult.Item toItem(ParamSearch search, Row row) {
        SearchCandidate candidate = row.candidate();
        CandidatePerformance performance = row.performance();
        return new CandidatePageResult.Item(
                CandidateId.of(search.getSearchId(), candidate.getCandidateNo()).value(),
                candidate.getCandidateNo(),
                candidate.getStrategyId(),
                new LinkedHashMap<>(candidate.getParameters().values()),
                candidate.getStatus().name(),
                row.view() == null ? null : row.view().backtestId(),
                performance == null ? null : performance.netReturn(),
                performance == null ? null : performance.maxDrawdown(),
                performance == null ? null : performance.tradingCost(),
                candidate.getStability(),
                candidate.getRetryCount(),
                candidate.getValidationLabel().name(),
                candidate.isSelected(),
                candidate.getSelectionReason());
    }

    /** 후보와 그 후보가 가리키는 실행. 실행이 없으면 {@code view}가 null이다(제외·미실행 후보). */
    private record Row(SearchCandidate candidate, CandidateRunView view) {

        CandidatePerformance performance() {
            return view == null ? null : view.performance();
        }
    }
}
