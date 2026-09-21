package com.ssafy.thorp.myrecord.presentation.controller;

import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery;
import com.ssafy.thorp.myrecord.application.result.RegimeRecordOutcome;
import com.ssafy.thorp.myrecord.application.service.MyRecordQueryService;
import com.ssafy.thorp.myrecord.application.service.MyRecordRegistrar;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordStatus;
import com.ssafy.thorp.myrecord.presentation.request.RecordRegimeSearchRequest;
import com.ssafy.thorp.myrecord.presentation.response.MyRecordDetailResponse;
import com.ssafy.thorp.myrecord.presentation.response.MyRecordListResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 기록 API(spec 006 contracts/my-records-api.md).
 *
 * <p>경로 전체가 로그인 필요다({@code SecurityConfig}의 {@code /my-records/**} → {@code authenticated()}). 소유자는
 * {@link CurrentRequester}로 구하고 남의 기록은 403이 아니라 404다(FR-020).
 *
 * <p>허용하지 않는 {@code sort}·{@code kind}·{@code status} 값은 400 {@code INVALID_INPUT}이다 — enum 인자 바인딩
 * 실패를 전역 처리기가 그렇게 감싼다. 조용히 기본값으로 바꾸지 않는다(원칙 VI).
 */
@RestController
@RequestMapping("/my-records")
public class MyRecordController {

    private final MyRecordQueryService queryService;
    private final MyRecordRegistrar registrar;
    private final CurrentRequester currentRequester;

    public MyRecordController(
            MyRecordQueryService queryService,
            MyRecordRegistrar registrar,
            CurrentRequester currentRequester) {
        this.queryService = queryService;
        this.registrar = registrar;
        this.currentRequester = currentRequester;
    }

    /** 목록. 기본 보기는 완료 항목이고 저장일 최신순이다(FR-004). 성과 정렬은 없다(research R7). */
    @GetMapping
    public ResponseEntity<ApiResponse<MyRecordListResponse>> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) MyRecordListQuery.RecordSort sort,
            @RequestParam(required = false) Set<RecordKind> kind,
            @RequestParam(required = false) Set<RecordStatus> status,
            @RequestParam(required = false) List<String> sector,
            @RequestParam(required = false) List<String> strategy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate periodFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate periodTo) {
        long ownerId = currentRequester.requireRequesterId();
        MyRecordListQuery query =
                new MyRecordListQuery(
                        kind,
                        status,
                        sector,
                        strategy,
                        periodFrom,
                        periodTo,
                        sort,
                        MyRecordListQuery.Cursor.parse(cursor),
                        size == null ? MyRecordListQuery.DEFAULT_SIZE : size);
        return ResponseEntity.ok(
                ApiResponse.ok(MyRecordListResponse.from(queryService.list(ownerId, query))));
    }

    /**
     * 경제환경 검색을 기록으로 남긴다(spec 006 T035, research R15). 010의 {@code GET /regime-searches}는 바뀌지 않는다.
     *
     * <p>이미 같은 (스냅샷·진입 경로)의 항목이 있으면 만들지 않고 200, 새로 만들면 201이다. 본문은 두 경우 모두 <b>목록 항목과 같다</b>.
     *
     * <p>{@code entryPath}·{@code baselineOrigin}은 화면이 보낸다 — 서버가 추정하지 않는다. 빠지거나 모르는 값이면 400 {@code
     * INVALID_INPUT}이고, 없는 {@code searchId}는 404다.
     */
    @PostMapping("/regime-searches")
    public ResponseEntity<ApiResponse<MyRecordListResponse.Item>> recordRegimeSearch(
            @Valid @RequestBody RecordRegimeSearchRequest request) {
        long ownerId = currentRequester.requireRequesterId();
        RegimeRecordOutcome outcome =
                registrar.recordRegimeSearch(
                        ownerId, request.searchId(), request.entryPath(), request.baselineOrigin());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(
                        ApiResponse.ok(
                                MyRecordListResponse.Item.from(
                                        queryService.item(ownerId, outcome.recordId()))));
    }

    /** 상세. 남의 것·없는 것·지운 것은 모두 404 {@code RESOURCE_NOT_FOUND}다. */
    @GetMapping("/{recordId}")
    public ResponseEntity<ApiResponse<MyRecordDetailResponse>> detail(
            @PathVariable String recordId) {
        long ownerId = currentRequester.requireRequesterId();
        return ResponseEntity.ok(
                ApiResponse.ok(
                        MyRecordDetailResponse.from(queryService.detail(ownerId, recordId))));
    }
}
