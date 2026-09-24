package com.ssafy.thorp.assistant.application.service.tools;

import com.ssafy.thorp.assistant.application.port.AssistantTool;
import com.ssafy.thorp.assistant.application.result.Evidence;
import com.ssafy.thorp.assistant.application.result.EvidenceKind;
import com.ssafy.thorp.assistant.application.result.ToolSpec;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult.Position;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 특정 날짜의 보유 종목·비중(계약 §2.2). 보유 종목 1개 = 근거 1개(비중). 스냅샷이 없는 날은 005와 같이 직전 스냅샷이고, 그 날짜가 {@code asOf}다.
 */
public class PositionsOnTool implements AssistantTool {
    static final String NAME = "getPositionsOn";
    private final ResultToolContext context;

    public PositionsOnTool(ResultToolContext context) {
        this.context = context;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME, "특정 날짜의 보유 종목과 비중", List.of("date"));
    }

    @Override
    public List<Evidence> run(Map<String, String> arguments) {
        LocalDate date = context.requireInPeriod(LocalDate.parse(arguments.get("date")), "date");
        BacktestPositionsResult result =
                context.publicResults().getPositions(context.token(), date);
        LocalDate asOf = result.snapshotDate() == null ? date : result.snapshotDate();
        String note = asOf.equals(date) ? "" : " (직전 보유 기준)";
        List<Evidence> facts = new ArrayList<>();
        if (result.positions().isEmpty()) {
            facts.add(
                    new Evidence(
                            null, "보유 종목" + note, "없음 (전부 현금)", asOf, "trades", EvidenceKind.TEXT));
            return facts;
        }
        for (Position position : result.positions()) {
            String name = context.label(position.instrumentId());
            facts.add(
                    new Evidence(
                            null,
                            name + " 보유 비중" + note,
                            ResultToolContext.plain(position.weight()),
                            asOf,
                            "trades",
                            EvidenceKind.RATIO));
        }
        return facts;
    }
}
