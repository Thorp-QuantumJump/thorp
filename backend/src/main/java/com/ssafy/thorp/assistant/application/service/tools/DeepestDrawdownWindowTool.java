package com.ssafy.thorp.assistant.application.service.tools;

import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult.Point;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 하락 폭이 가장 낮은 날과 그 앞 고점 날(계약 §2.2).
 *
 * <p><b>정의는 005 화면의 {@code frontend/src/components/result/drawdownWindow.ts}와 같다</b>: 가장 낮은 {@code
 * drawdown}의 첫날이 저점, 그 앞에서 마지막으로 {@code drawdown >= 0}인 날이 고점(없으면 첫 점). 저점 값이 {@code
 * summary.maxDrawdown}과 다르면 구간을 정하지 않고 빈 결과다 — 화면과 다른 구간을 만들지 않는다. 두 벌인 것은 빚이고, 워커가 {@code
 * analytics.maxDrawdownWindow}를 담으면 둘 다 그 값을 읽는다 (plan "후속").
 */
public class DeepestDrawdownWindowTool implements AssistantTool {
    static final String NAME = "getDeepestDrawdownWindow";
    private final ResultToolContext context;

    public DeepestDrawdownWindowTool(ResultToolContext context) {
        this.context = context;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.dates(NAME, "자산이 가장 크게 줄었던 구간: 그 앞 고점 날과 가장 낮은 날. 인자 없음", List.of());
    }

    @Override
    public List<Evidence> run(Map<String, String> arguments) {
        BigDecimal expected = context.maxDrawdown();
        if (expected == null) {
            return List.of();
        }
        List<Point> points = context.publicResults().getTimeseries(context.token()).points();
        int troughIndex = -1;
        BigDecimal troughValue = null;
        for (int i = 0; i < points.size(); i++) {
            BigDecimal drawdown = points.get(i).drawdown();
            if (drawdown != null && (troughValue == null || drawdown.compareTo(troughValue) < 0)) {
                troughValue = drawdown;
                troughIndex = i;
            }
        }
        if (troughIndex < 0 || troughValue.signum() >= 0 || troughValue.compareTo(expected) != 0) {
            return List.of();
        }
        int peakIndex = 0;
        for (int i = troughIndex - 1; i >= 0; i--) {
            BigDecimal drawdown = points.get(i).drawdown();
            if (drawdown != null && drawdown.signum() >= 0) {
                peakIndex = i;
                break;
            }
        }
        Point peak = points.get(peakIndex);
        Point trough = points.get(troughIndex);
        return List.of(
                new Evidence(
                        null,
                        "하락 시작(고점) 날짜",
                        peak.date().toString(),
                        peak.date(),
                        "loss",
                        EvidenceKind.DATE),
                new Evidence(
                        null,
                        "고점 자산가치",
                        ResultToolContext.plain(peak.equity()),
                        peak.date(),
                        "loss",
                        EvidenceKind.MONEY),
                new Evidence(
                        null,
                        "가장 크게 줄어든 날짜",
                        trough.date().toString(),
                        trough.date(),
                        "loss",
                        EvidenceKind.DATE),
                new Evidence(
                        null,
                        "저점 자산가치",
                        ResultToolContext.plain(trough.equity()),
                        trough.date(),
                        "loss",
                        EvidenceKind.MONEY),
                new Evidence(
                        null,
                        "저점 하락 폭",
                        ResultToolContext.plain(trough.drawdown()),
                        trough.date(),
                        "loss",
                        EvidenceKind.RATIO));
    }
}
