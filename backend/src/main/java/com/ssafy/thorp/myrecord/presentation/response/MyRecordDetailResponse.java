package com.ssafy.thorp.myrecord.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.myrecord.application.result.MappingDrift;
import com.ssafy.thorp.myrecord.application.result.MyRecordDetail;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.application.result.RegistryDrift;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /my-records/{recordId}} 응답(contracts). 목록 항목에 펼쳐 보는 층을 더한다.
 *
 * <p>{@code mappingDrift}·{@code registryDrift}가 없으면 키를 만들지 않는다 — 판정할 근거(저장 당시 대응표 버전, 등록표의 축)가
 * 없었다는 뜻이고, {@code changed:false}·{@code outOfRange:false}로 답하면 "안 바뀜"과 "모름"이 섞인다(원칙 VI).
 *
 * <p>{@code reopenPayload}는 {@code ANALYSIS}에만 있고 005 {@code GET /results/&#123;token&#125;}의
 * {@code config}와 <b>같은 모양</b>이다. 화면은 그대로 {@code prefillStrategySettings}에 넘긴다(research R4).
 */
public record MyRecordDetailResponse(
        String recordId,
        RecordKind kind,
        String name,
        List<String> sectorCodes,
        List<String> strategyIds,
        MyRecordListResponse.Period period,
        Instant savedAt,
        Instant lastViewedAt,
        RecordStatus status,
        String statusReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) String resultToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String searchId,
        @JsonInclude(JsonInclude.Include.NON_NULL) MyRecordListResponse.Regime regime,
        boolean isCopy,
        Map<String, Object> referenceVersions,
        @JsonInclude(JsonInclude.Include.NON_NULL) MappingDrift mappingDrift,
        @JsonInclude(JsonInclude.Include.NON_NULL) RegistryDrift registryDrift,
        ReopenSettings reopenSettings,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> reopenPayload) {

    /** 계약의 {@code reopenSettings}. 이유를 나누지 않는다 — 화면은 버튼을 감출지만 정한다. */
    public record ReopenSettings(boolean available) {}

    public static MyRecordDetailResponse from(MyRecordDetail detail) {
        MyRecordView view = detail.view();
        MyRecordListResponse.Item item = MyRecordListResponse.Item.from(view);
        return new MyRecordDetailResponse(
                item.recordId(),
                item.kind(),
                item.name(),
                item.sectorCodes(),
                item.strategyIds(),
                item.period(),
                item.savedAt(),
                view.lastViewedAt(),
                item.status(),
                item.statusReason(),
                item.resultToken(),
                item.searchId(),
                item.regime(),
                item.isCopy(),
                detail.referenceVersions(),
                detail.mappingDrift(),
                detail.registryDrift(),
                new ReopenSettings(detail.reopenSettings().available()),
                detail.reopenPayload());
    }
}
