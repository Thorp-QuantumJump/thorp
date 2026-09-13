package com.ssafy.thorp.sector.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import com.ssafy.thorp.sector.application.result.InstrumentProfileResult;
import com.ssafy.thorp.sector.domain.model.InstrumentProfile;
import com.ssafy.thorp.sector.domain.model.RepresentativeEtf;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /sectors/{code}/instrument-profile} 응답 본문. 계약은 contracts/sector-api.md다.
 *
 * <p>금액·비율에는 {@code @JsonSerialize(using = PlainBigDecimalSerializer.class)}를 <b>필드마다</b> 붙인다. 웹
 * ObjectMapper에 전역 등록하면 다른 도메인의 {@code MarketResponse.value}가 숫자에서 문자열로 바뀌어 그 API 계약이 깨진다({@code
 * backend/CLAUDE.md}).
 *
 * <p><b>추적 차이 필드가 없다.</b> 계산 정의가 확정되지 않아 이번 범위에서 뺐다(PD-21, spec.md Out of Scope). 나중에 정의가 확정되면 필드를
 * 추가하는 방향으로 넓힌다(contracts/sector-api.md).
 */
public record InstrumentProfileResponse(
        String instrumentId,
        String name,
        String benchmarkIndex,
        String indexMethodologySummary,
        LocalDate listedOn,
        String manager,
        HoldingsOverlap holdingsOverlap,
        @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal avgDailyValue3m,
        String sourceAttribution,
        String survivorshipNote,
        String datasetId,
        Instant dataDeadline,
        boolean isSample) {

    public static InstrumentProfileResponse from(InstrumentProfileResult result) {
        RepresentativeEtf etf = result.etf();
        InstrumentProfile profile = result.profile();
        return new InstrumentProfileResponse(
                etf.instrumentId(),
                etf.name(),
                etf.benchmarkIndex(),
                profile.indexMethodologySummary(),
                etf.listedOn(),
                etf.manager(),
                HoldingsOverlap.from(profile),
                profile.avgDailyValue3m(),
                profile.sourceAttribution(),
                profile.survivorshipNote(),
                result.datasetId(),
                result.dataDeadline(),
                result.isSample());
    }

    /**
     * 구성종목 중복.
     *
     * <p>{@code asOfCurrentComposition}이 true면 화면은 당시 구성을 재현했다고 표현하지 않는다(FR-015). 저장 스키마에서는 이 값이
     * {@code holdings_overlap} 밖의 별도 컬럼이지만, 계약이 한 객체로 묶어 내보내기로 정해 여기서 합친다 — 표시와 단서를 떼어 놓으면 화면이 단서를
     * 빠뜨리기 쉽다.
     */
    public record HoldingsOverlap(boolean asOfCurrentComposition, List<HoldingItem> topHoldings) {

        static HoldingsOverlap from(InstrumentProfile profile) {
            return new HoldingsOverlap(
                    profile.asOfCurrentComposition(),
                    profile.topHoldings().stream().map(HoldingItem::from).toList());
        }
    }

    /** 구성종목 한 건. 비중은 1을 100%로 보는 비율이며 문자열로 나간다. */
    public record HoldingItem(
            String name,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal weight) {

        static HoldingItem from(InstrumentProfile.Holding holding) {
            return new HoldingItem(holding.name(), holding.weight());
        }
    }
}
