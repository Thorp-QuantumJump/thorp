package com.ssafy.thorp.regime.presentation.response;

import com.ssafy.thorp.regime.domain.vo.SearchStatus;
import com.ssafy.thorp.regime.domain.vo.StatusReason;
import java.time.YearMonth;

/**
 * 계약이 고정한 문구 (계약 §1, spec FR-019·FR-020·FR-027).
 *
 * <p><b>문구를 서버가 소유한다.</b> 화면이 상태 코드를 보고 말을 지어내면 같은 상태가 화면마다 다른 뜻으로 읽히고, 그 어긋남은 사용자가 결과를 어디까지 믿어도
 * 되는지를 흐린다(spec FR-037). 그래서 FE는 {@code statusNote}·{@code caseLabel}·{@code basis.note}를 그대로 쓴다.
 */
public final class RegimeNotes {

    /** 문턱 미교정 상태의 고정 문구 (spec FR-020, research R6). */
    public static final String NEAREST_ONLY_NOTE = "가까운 순서만 제공합니다. 유사성은 아직 판정하지 않습니다.";

    /**
     * 교정된 기준으로 나눈 결과의 고정 문구 (spec FR-019·FR-021, threshold-calibration.md §6). 기준 충족은 Thorp가 정한 기준을
     * 넘었다는 뜻이지 그 시기가 지금과 같다는 뜻이 아니다 — 그렇게 읽히는 말을 쓰지 않는다(FR-022).
     */
    public static final String THRESHOLD_APPLIED_NOTE =
            "Thorp가 정한 유사성 기준으로 사례를 나눴습니다. 기준을 넘지 못한 사례는 참고 사례로 따로 봅니다.";

    /** 교정된 결과에서 따로 여는 목록의 이름 (spec FR-021). */
    public static final String REFERENCE_LABEL = "기준 미충족 참고 사례";

    /** 분야 검색에서 기준 충족 사례가 없을 때 (spec US1 시나리오 5). */
    public static final String NO_MEETING_FIELD_NOTE = "설정된 유사성 기준을 충족하는 사례가 없습니다.";

    /** 종합 비교에서 기준 충족 사례가 없을 때 (spec US3 시나리오 4). 분야별 탐색으로 잇는다. */
    public static final String NO_MEETING_COMPOSITE_NOTE =
            "설정된 종합 유사성 기준을 충족하는 사례가 없습니다. 분야별로 살펴볼 수 있습니다.";

    /** 문턱 교정 전 사례에 붙는 표시 (spec FR-020). {@code THRESHOLD_APPLIED}가 되면 사라진다. */
    public static final String CASE_LABEL = "최근접 참고 사례";

    /** 역사 비교 표시 (spec FR-027, SC-009). <b>항상 이 값이다</b> — 빈티지 자료를 확보하기 전에는 다른 값이 나오지 않는다. */
    public static final String BASIS_KIND = "HISTORICAL_REVISION";

    public static final String BASIS_NOTE = "최신 개정자료를 이용한 역사 비교입니다. 당시 발표값으로 재현한 분석이 아닙니다.";

    /** 표시 상한에 걸렸을 때의 안내 (spec FR-013, SC-004). <b>후보 수를 함께 말하지 않는다.</b> */
    public static final String DISPLAY_LIMIT_NOTE = "가까운 순서로 10개까지 보입니다.";

    private RegimeNotes() {}

    /** 계산 불가 사유별 안내. 사용자가 할 수 있는 일이 사유마다 달라 한 문구로 뭉치지 않는다. */
    public static String statusNote(SearchStatus status, StatusReason reason) {
        if (status == SearchStatus.THRESHOLD_APPLIED) {
            return THRESHOLD_APPLIED_NOTE;
        }
        if (status != SearchStatus.NOT_COMPUTABLE) {
            return NEAREST_ONLY_NOTE;
        }
        if (reason == null) {
            return "지금은 계산할 수 없습니다.";
        }
        return switch (reason) {
            case SCALE_WINDOW_TOO_SHORT -> "비교 기준을 만들 만큼 과거 자료가 이어지지 않아 계산할 수 없습니다.";
            case SCALE_ZERO -> "이 분야는 값이 거의 변하지 않아 계산할 수 없습니다.";
            case QUERY_INPUT_MISSING -> "기준월 자료가 없는 지표가 있어 계산할 수 없습니다.";
            case NO_CANDIDATE -> "비교할 수 있는 과거 시점이 없습니다.";
            case THRESHOLD_NOT_CALIBRATED -> NEAREST_ONLY_NOTE;
        };
    }

    /** 자료 기준 시점 안내 (spec FR-031). 분야 이름과 대상월만 말하고 <b>수집 시각은 쓰지 않는다</b>. */
    public static String dataAsOfNote(String scopeName, YearMonth targetMonth) {
        if (scopeName == null || targetMonth == null) {
            return null;
        }
        return "%s 지표는 %d년 %d월분까지 반영되어 있습니다."
                .formatted(scopeName, targetMonth.getYear(), targetMonth.getMonthValue());
    }

    /** 비교 시점에서 빠진 달 표기 (spec FR-031, SC-007). 어느 지표의 어느 달인지 말한다. */
    public static String omittedNote(String indicatorName, java.util.List<YearMonth> omitted) {
        if (indicatorName == null || omitted == null || omitted.isEmpty()) {
            return null;
        }
        String months =
                omitted.stream()
                        .map(month -> "%d년 %d월".formatted(month.getYear(), month.getMonthValue()))
                        .reduce((left, right) -> left + "·" + right)
                        .orElse("");
        return "%s은(는) %s 자료가 없어 그 달을 빼고 비교했습니다.".formatted(indicatorName, months);
    }
}
