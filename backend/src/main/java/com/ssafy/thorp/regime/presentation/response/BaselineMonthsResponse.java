package com.ssafy.thorp.regime.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.regime.application.result.BaselineMonthsResult;
import java.util.List;

/**
 * 화면이 기준월 선택과 모드 전환 안내를 만들 때 쓴다 (계약 §5).
 *
 * <p>{@code composite}가 {@code byField}의 어느 값보다 이를 수 있다 — 발표 시차가 가장 긴 지표를 따르기 때문이다. 그래서 모드를 바꾸면
 * 기준월이 달라지고, 화면은 {@code note}로 그 사실을 한 문장으로 알린다(spec US3 시나리오 1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaselineMonthsResponse(
        List<FieldBaselineView> byField, String composite, RangeView range, String note) {

    /** 모드 전환 시 한 문장 알림의 고정 문구 (계약 §5). */
    public static final String MODE_SWITCH_NOTE = "전체 분야를 맞추기 위해 비교 기준월이 바뀝니다.";

    public static BaselineMonthsResponse from(BaselineMonthsResult result) {
        return new BaselineMonthsResponse(
                result.byField().stream().map(FieldBaselineView::of).toList(),
                RegimeFormat.month(result.composite()),
                RangeView.of(result),
                MODE_SWITCH_NOTE);
    }

    public record FieldBaselineView(String id, String name, String baselineMonth) {
        static FieldBaselineView of(BaselineMonthsResult.FieldBaseline item) {
            return new FieldBaselineView(
                    item.field().id(),
                    item.field().name(),
                    RegimeFormat.month(item.baselineMonth()));
        }
    }

    /** 사전 계산이 실제로 있는 구간. 이 밖의 달을 고르면 404다(계약 §1 오류표). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RangeView(String earliest, String latest) {
        static RangeView of(BaselineMonthsResult result) {
            if (result.earliest() == null && result.latest() == null) {
                return null;
            }
            return new RangeView(
                    RegimeFormat.month(result.earliest()), RegimeFormat.month(result.latest()));
        }
    }
}
