package com.ssafy.thorp.assistant.application.service.tools;

import com.ssafy.thorp.assistant.application.exception.ToolUnavailableException;
import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult.Point;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 특정 날짜의 시계열 점 하나(계약 §2.2). 시계열에 없는 날(휴장)은 직전 거래일로 바꾸고 라벨에 적는다. */
public class EquityOnTool implements AssistantTool {
    static final String NAME = "getEquityOn";
    private final ResultToolContext context;

    public EquityOnTool(ResultToolContext context) {
        this.context = context;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.dates(NAME, "특정 날짜의 자산가치·누적 수익률·하락 폭. 휴장일이면 직전 거래일 값", List.of("date"));
    }

    @Override
    public List<Evidence> run(Map<String, String> arguments) {
        LocalDate date = context.requireInPeriod(LocalDate.parse(arguments.get("date")), "date");
        List<Point> points = context.publicResults().getTimeseries(context.token()).points();
        Point picked = null;
        for (Point point : points) {
            if (point.date().isAfter(date)) {
                break;
            }
            picked = point;
        }
        if (picked == null) {
            throw new ToolUnavailableException("그 날짜 앞의 시계열 자료가 없습니다");
        }
        String note = picked.date().equals(date) ? "" : " (휴장일이라 직전 거래일 값)";
        return pointFacts(picked, "", note);
    }

    /** 점 하나를 자산가치·누적 수익률·하락 폭 세 근거로. {@code benchmarkReturn}·{@code cashFlows}는 넣지 않는다(계약 §3). */
    static List<Evidence> pointFacts(Point point, String prefix, String suffix) {
        return List.of(
                new Evidence(
                        null,
                        prefix + "자산가치" + suffix,
                        ResultToolContext.plain(point.equity()),
                        point.date(),
                        "summary",
                        EvidenceKind.MONEY),
                new Evidence(
                        null,
                        prefix + "누적 수익률" + suffix,
                        ResultToolContext.plain(point.cumulativeReturn()),
                        point.date(),
                        "summary",
                        EvidenceKind.RATIO),
                new Evidence(
                        null,
                        prefix + "하락 폭" + suffix,
                        ResultToolContext.plain(point.drawdown()),
                        point.date(),
                        "loss",
                        EvidenceKind.RATIO));
    }
}
