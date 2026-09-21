package com.ssafy.thorp.myrecord.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.myrecord.application.result.MyRecordPage;
import com.ssafy.thorp.myrecord.application.result.MyRecordView;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.myrecord.domain.model.RecordStatus;
import com.ssafy.thorp.myrecord.domain.vo.AnalysisPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /my-records} 응답(contracts). <b>수익률·하락 폭·비용은 없다</b> — 목록에서 성과를 나란히 보이면 순위 화면이 된다(원칙
 * III).
 *
 * <p>종류별 열쇠({@code resultToken}·{@code searchId}·{@code regime})는 그 종류에만 있고 다른 종류에는 키를 만들지 않는다.
 * {@code statusReason}·{@code period}는 없을 때 명시적 null 이다 — 계약 예시가 그렇다.
 */
public record MyRecordListResponse(List<Item> items, String nextCursor) {

    public static MyRecordListResponse from(MyRecordPage page) {
        return new MyRecordListResponse(
                page.items().stream().map(Item::from).toList(),
                page.nextCursor() == null ? null : page.nextCursor().encode());
    }

    public record Item(
            String recordId,
            RecordKind kind,
            String name,
            List<String> sectorCodes,
            List<String> strategyIds,
            Period period,
            Instant savedAt,
            RecordStatus status,
            String statusReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) String resultToken,
            @JsonInclude(JsonInclude.Include.NON_NULL) String searchId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Regime regime,
            boolean isCopy) {

        public static Item from(MyRecordView view) {
            return new Item(
                    view.recordId(),
                    view.kind(),
                    view.name(),
                    view.sectorCodes(),
                    view.strategyIds(),
                    Period.from(view.period()),
                    view.savedAt(),
                    view.status(),
                    view.statusReason(),
                    view.resultToken(),
                    view.searchId(),
                    Regime.from(view.regime()),
                    view.isCopy());
        }
    }

    public record Period(LocalDate start, LocalDate end) {
        static Period from(AnalysisPeriod period) {
            return period == null ? null : new Period(period.start(), period.end());
        }
    }

    /**
     * '다시 보기'는 {@code /regime-search?searchId={snapshotKey}}로 저장 revision 을 고정 조회한다(research R17).
     */
    public record Regime(
            String snapshotKey,
            String mode,
            String fieldId,
            LocalDate baselineMonth,
            String baselineOrigin,
            String entryPath,
            boolean withdrawn) {
        static Regime from(MyRecordView.Regime regime) {
            if (regime == null) {
                return null;
            }
            return new Regime(
                    regime.key().toString(),
                    regime.key().mode().name(),
                    regime.key().fieldId(),
                    regime.key().baselineMonth(),
                    regime.baselineOrigin().name(),
                    regime.entryPath().name(),
                    regime.withdrawn());
        }
    }
}
