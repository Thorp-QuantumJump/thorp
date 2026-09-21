package com.ssafy.thorp.myrecord.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.backtest.application.service.BacktestResultQueryService;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.myrecord.application.result.MyRecordExport;
import com.ssafy.thorp.myrecord.application.result.MyRecordExport.Exclusion;
import com.ssafy.thorp.myrecord.application.result.MyRecordExport.Failure;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.domain.model.RecordStatus;
import com.ssafy.thorp.paramsearch.application.command.FindCandidatesQuery;
import com.ssafy.thorp.paramsearch.application.result.CandidatePageResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.CandidateQueryService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.regime.application.repository.RegimeSnapshotRepository;
import com.ssafy.thorp.regime.domain.model.RegimeSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 기록 내보내기(spec 006 US4, FR-015·FR-016, research R10). 서버가 조립한다 — 이용 범위 판정과 "무엇을 뺐는지"의 규칙이 한 곳에
 * 있어야 한다.
 *
 * <p><b>계산하지 않고 원본의 읽기 경로를 그대로 부른다.</b> 분석은 005의 {@link BacktestResultQueryService}(결과 화면과 같은 값),
 * 탐색 묶음은 007의 조회 서비스, 경제환경은 010 스냅샷이다. 006이 값을 다시 꺼내 모양을 맞추면 화면과 파일이 다른 값을 보이게 된다.
 *
 * <p>상태 규칙(spec FR-015): 진행 중은 409 {@code RESULT_NOT_READY}. 실패·취소(·철회)는 설정값·참조 버전·사유·{@code
 * exclusions}만 담는다. 완료·부분 완료는 전부 담는다.
 */
@Service
@Transactional(readOnly = true)
public class MyRecordExportService {

    /** 재배포가 금지된 원천 항목. 스키마에 자리가 없고(원칙 X) 파일 안에 그 사실을 적는다(FR-016). 종류·상태와 무관하게 늘 들어간다. */
    static final List<Exclusion> SOURCE_EXCLUSIONS =
            List.of(
                    new Exclusion(
                            "dailyPriceRaw",
                            "SOURCE_REDISTRIBUTION_FORBIDDEN",
                            "원천 시세의 일별 원값은 이용 조건상 내보낼 수 없습니다."),
                    new Exclusion(
                            "newsArticleBody",
                            "SOURCE_REDISTRIBUTION_FORBIDDEN",
                            "뉴스 기사 본문은 이용 조건상 내보낼 수 없습니다."));

    private static final Exclusion FILLS_NOT_RECORDED =
            new Exclusion("fills", "NOT_RECORDED", "이 결과는 개별 체결을 기록하지 않았습니다. 체결이 없었다는 뜻이 아닙니다.");

    private static final int TRADES_PAGE = 1000;

    private final MyRecordQueryService records;
    private final BacktestRequestRepository requests;
    private final BacktestResultQueryService results;
    private final BacktestResultRepository resultRows;
    private final ParamSearchService paramSearches;
    private final CandidateQueryService candidates;
    private final RegimeSnapshotRepository snapshots;
    private final ObjectMapper mapper;

    public MyRecordExportService(
            MyRecordQueryService records,
            BacktestRequestRepository requests,
            BacktestResultQueryService results,
            BacktestResultRepository resultRows,
            ParamSearchService paramSearches,
            CandidateQueryService candidates,
            RegimeSnapshotRepository snapshots,
            ObjectMapper mapper) {
        this.records = records;
        this.requests = requests;
        this.results = results;
        this.resultRows = resultRows;
        this.paramSearches = paramSearches;
        this.candidates = candidates;
        this.snapshots = snapshots;
        this.mapper = mapper;
    }

    /**
     * 남의 것·없는 것·지운 것은 404({@code MyRecordQueryService}와 같은 문장). 열람 표시는 남기지 않는다 — 파일을 받는 것은 다시 본 것이
     * 아니다.
     */
    public MyRecordExport export(long ownerId, String recordId) {
        MyRecordView view = records.item(ownerId, recordId);
        if (view.status() == RecordStatus.RUNNING) {
            throw new BusinessException(
                    ErrorCode.RESULT_NOT_READY, "진행 중인 기록은 내보낼 수 없습니다: " + recordId);
        }
        return switch (view.kind()) {
            case ANALYSIS -> analysis(ownerId, view);
            case SEARCH_BUNDLE -> bundle(view);
            case REGIME_SEARCH -> regime(view);
        };
    }

    // -- 분석 -------------------------------------------------------------------------

    private MyRecordExport analysis(long ownerId, MyRecordView view) {
        BacktestRequest request = requestOf(ownerId, view);
        BacktestDetailResult detail = results.getDetail(request);
        Failure failure = failureOf(detail, view);
        if (!complete(view)) {
            return minimal(view, detail.config(), failure);
        }

        List<EngineOutput.FillResult> fills =
                resultRows.findFills(view.analysisRunId(), null, null).orElse(null);
        List<Exclusion> exclusions = new ArrayList<>(SOURCE_EXCLUSIONS);
        if (fills == null) {
            exclusions.add(FILLS_NOT_RECORDED);
        }
        return new MyRecordExport(
                record(view),
                detail.config(),
                records.referenceVersions(view),
                failure,
                detail.summary(),
                results.getTimeseries(request).points(),
                allTrades(request),
                fills,
                results.getExceptions(request).items(),
                null,
                null,
                null,
                exclusions);
    }

    /**
     * 기록이 가리키는 실행에 대한 <b>소유자의</b> 요청. 목록 뷰가 결과 토큰을 고르는 규칙과 같다 — 취소되지 않은 것을 먼저, 그다음 가장 최근 것.
     *
     * <p>요청이 하나도 없으면 접수 이벤트가 만든 항목과 요청 표가 어긋난 것이라 500이다. 조용히 빈 파일을 만들지 않는다.
     */
    private BacktestRequest requestOf(long ownerId, MyRecordView view) {
        return requests.findByRunId(view.analysisRunId()).stream()
                .filter(r -> r.isOwnedBy(ownerId) && r.getExecutionType() == ExecutionType.SINGLE)
                .min(
                        Comparator.comparing(BacktestRequest::isCancelled)
                                .thenComparing(BacktestRequest::getId, Comparator.reverseOrder()))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "기록이 가리키는 실행에 소유자의 요청이 없습니다: " + view.recordId()));
    }

    /**
     * 거래 전체. 005의 쪽 조회를 끝까지 넘긴다 — 파일에는 쪽이 없다.
     *
     * <p>ponytail: 파일 전체(거래·후보 목록과 CSV 문자열)를 메모리에 만든다. 지금 규모(거래 수백 건, 시계열 ~1700점, 후보는 상한이 있다)에서는
     * 충분하고, 거래·후보가 수만 건이 되면 {@code StreamingResponseBody}로 바꾼다.
     */
    private List<EngineOutput.TradeResult> allTrades(BacktestRequest request) {
        List<EngineOutput.TradeResult> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            BacktestTradePageResult result =
                    results.getTrades(
                            request, new FindBacktestTradesQuery(page, TRADES_PAGE, null, null));
            all.addAll(result.content());
            if (page + 1 >= result.totalPages()) {
                return all;
            }
        }
    }

    private static Failure failureOf(BacktestDetailResult detail, MyRecordView view) {
        if (detail.failure() != null) {
            return new Failure(detail.failure().code(), detail.failure().message());
        }
        return statusFailure(view);
    }

    // -- 탐색 묶음 --------------------------------------------------------------------

    private MyRecordExport bundle(MyRecordView view) {
        ParamSearchResult search = paramSearches.get(view.searchId());
        ParamSearchResult.Config config = search.config();
        Map<String, Object> configOut = new LinkedHashMap<>();
        configOut.put("strategyIds", config.strategyIds());
        configOut.put("instrumentIds", config.instrumentIds());
        configOut.put("searchMethod", config.searchMethod());
        configOut.put("randomSeed", config.randomSeed());
        configOut.put("limits", config.limits());
        Failure failure = statusFailure(view);
        if (!complete(view)) {
            return minimal(view, configOut, failure);
        }

        Map<String, Object> searchSpace = new LinkedHashMap<>();
        searchSpace.put("strategies", config.space());
        searchSpace.put("selectionRule", config.selectionRule());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", search.status());
        summary.put("stopReason", search.stopReason());
        summary.put("counts", search.counts());
        summary.put("partial", search.partial());
        summary.put("partialNote", search.partialNote());
        summary.put("baseline", search.baseline());
        summary.put("validationLabel", search.validationLabel());
        summary.put("selection", search.selection());
        summary.put("startedAt", search.startedAt());
        summary.put("finishedAt", search.finishedAt());

        return new MyRecordExport(
                record(view),
                configOut,
                records.referenceVersions(view),
                failure,
                summary,
                null,
                null,
                null,
                null,
                searchSpace,
                allCandidates(view.searchId()),
                null,
                SOURCE_EXCLUSIONS);
    }

    /** 후보 전체를 순번 순으로. 007의 쪽 조회를 끝까지 넘긴다. */
    private List<CandidatePageResult.Item> allCandidates(String searchId) {
        List<CandidatePageResult.Item> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            CandidatePageResult result =
                    candidates.find(
                            searchId,
                            new FindCandidatesQuery(
                                    page,
                                    FindCandidatesQuery.MAX_SIZE,
                                    null,
                                    null,
                                    FindCandidatesQuery.Sort.CANDIDATE_NO,
                                    false));
            all.addAll(result.content());
            if (page + 1 >= result.totalPages()) {
                return all;
            }
        }
    }

    // -- 경제환경 ---------------------------------------------------------------------

    /**
     * 010 스냅샷을 그대로 담는다(FR-012). 모드·분야·기준월은 {@code config}(검색 설정, FR-013 별도 축), 스냅샷 내용은 {@code
     * regime}이다. DB PK는 뺀다(원칙 VIII). 011 연결 표가 아직 없어 {@code link}는 만들지 않는다(원칙 VI).
     *
     * <p>철회된 항목(스냅샷 참조가 끊김)은 설정과 사유만 담는다 — 지금 최신 판을 대신 넣지 않는다(동일 버전 조회).
     */
    private MyRecordExport regime(MyRecordView view) {
        MyRecordView.Regime regime = view.regime();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", regime.key().mode());
        config.put("fieldId", regime.key().fieldId());
        config.put("baselineMonth", regime.key().baselineMonth());
        config.put("baselineOrigin", regime.baselineOrigin());
        config.put("entryPath", regime.entryPath());
        Failure failure = statusFailure(view);
        if (regime.withdrawn()) {
            return minimal(view, config, failure);
        }
        RegimeSnapshot snapshot =
                snapshots
                        .findByKey(regime.key().toString())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "기록이 가리키는 경제환경 스냅샷이 없습니다: " + view.recordId()));
        ObjectNode content = mapper.valueToTree(snapshot);
        content.remove("id");
        return new MyRecordExport(
                record(view),
                config,
                records.referenceVersions(view),
                failure,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                content,
                SOURCE_EXCLUSIONS);
    }

    // -- 공통 -------------------------------------------------------------------------

    private static boolean complete(MyRecordView view) {
        return view.status() != RecordStatus.FAILED && view.status() != RecordStatus.CANCELLED;
    }

    /** 실패·취소 사유(FR-015). 정상 완료면 null — 사유 자리를 빈 값으로 두지 않는다. */
    private static Failure statusFailure(MyRecordView view) {
        if (complete(view) && view.status() != RecordStatus.WITHDRAWN) {
            return null;
        }
        String code = view.statusReason() != null ? view.statusReason() : view.status().name();
        return new Failure(code, null);
    }

    private MyRecordExport minimal(MyRecordView view, Map<String, Object> config, Failure failure) {
        return new MyRecordExport(
                record(view),
                config,
                records.referenceVersions(view),
                failure,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                SOURCE_EXCLUSIONS);
    }

    private static Map<String, Object> record(MyRecordView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordId", view.recordId());
        out.put("name", view.name());
        out.put("kind", view.kind());
        out.put("savedAt", view.savedAt());
        out.put("status", view.status());
        out.put("statusReason", view.statusReason());
        return out;
    }
}
