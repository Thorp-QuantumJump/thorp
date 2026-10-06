package com.ssafy.thorp.assistant.application.service.tools;

import com.ssafy.thorp.assistant.application.exception.ToolUnavailableException;
import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult.Point;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 구간의 첫·끝·최고·최저 점 넷(계약 §2.2). 응답 값에서 <b>고르기만</b> 한다 — 새 점이나 구간 수익률을 만들지 않는다(아키텍처 원칙 1). */
public class EquityRangeTool implements AssistantTool {
    static final String NAME = "getEquityRange";
    private final ResultToolContext context;

    public EquityRangeTool(ResultToolContext context) {
        this.context = context;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.dates(NAME, "구간(최대 1년)의 첫날·마지막 날·자산가치 최고일·최저일 점", List.of("from", "to"));
    }

    @Override
    public List<Evidence> run(Map<String, String> arguments) {
        LocalDate from = context.requireInPeriod(LocalDate.parse(arguments.get("from")), "from");
        LocalDate to = context.requireInPeriod(LocalDate.parse(arguments.get("to")), "to");
        context.requireOrdered(from, to);
        if (from.plusYears(1).isBefore(to)) {
            throw new ToolUnavailableException("구간은 1년 이하여야 합니다");
        }
        List<Point> inRange = new ArrayList<>();
        for (Point point : context.publicResults().getTimeseries(context.token()).points()) {
            if (!point.date().isBefore(from) && !point.date().isAfter(to)) {
                inRange.add(point);
            }
        }
        if (inRange.isEmpty()) {
            throw new ToolUnavailableException("그 구간의 시계열 자료가 없습니다");
        }
        Point first = inRange.get(0);
        Point last = inRange.get(inRange.size() - 1);
        Point highest = first;
        Point lowest = first;
        for (Point point : inRange) {
            if (point.equity() != null && point.equity().compareTo(highest.equity()) > 0) {
                highest = point;
            }
            if (point.equity() != null && point.equity().compareTo(lowest.equity()) < 0) {
                lowest = point;
            }
        }
        List<Evidence> facts = new ArrayList<>();
        facts.addAll(EquityOnTool.pointFacts(first, "구간 첫날 ", ""));
        facts.addAll(EquityOnTool.pointFacts(last, "구간 마지막 날 ", ""));
        facts.addAll(EquityOnTool.pointFacts(highest, "구간 최고일 ", ""));
        facts.addAll(EquityOnTool.pointFacts(lowest, "구간 최저일 ", ""));
        return facts;
    }
}
