package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 요청 사본의 왕복(T010). 접수가 쓴 것을 재시도가 읽어 <b>같은 명령</b>이 나와야 한다 — 한 칸이라도 새면 재시도가 다른 조건으로 계산된다. */
class RequestSpecCodecTest {

    private static final String COST_PROFILE = "COST-2026-09";

    private static CreateBacktestCommand command(
            BigDecimal targetWeight,
            Integer warmup,
            Scenario scenario,
            Map<String, Object> params) {
        return new CreateBacktestCommand(
                7L,
                ExecutionType.SINGLE,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", targetWeight),
                        new CreateBacktestCommand.AllocationItem("BATTERY", "305720.XKRX", null)),
                "FIXED_WEIGHT",
                "1",
                "PARAM-2026-09",
                Map.of("rebalance", "MONTH_END"),
                LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 12, 31),
                new BigDecimal("10000000.00"),
                COST_PROFILE,
                "SPLIT-1",
                warmup,
                scenario,
                params);
    }

    private static CreateBacktestCommand roundTrip(CreateBacktestCommand command, int warmup) {
        Map<String, Object> spec = RequestSpecCodec.write(command, command.scenario(), warmup);
        return RequestSpecCodec.toCommand(
                spec, command.requesterId(), command.executionType(), COST_PROFILE);
    }

    @Test
    @DisplayName("접수가 쓴 사본을 읽으면 같은 명령이 나온다 — 실효 워밍업만 확정값으로 채워진다")
    void writeThenReadYieldsTheSameCommand() {
        CreateBacktestCommand original =
                command(new BigDecimal("0.4000"), null, Scenario.FULL_PERIOD, null);

        CreateBacktestCommand rebuilt = roundTrip(original, 63);

        // 요청이 비워 둔 워밍업은 접수가 확정한 실효값으로 채워진다. 나머지는 그대로다.
        assertThat(rebuilt)
                .isEqualTo(command(new BigDecimal("0.4000"), 63, Scenario.FULL_PERIOD, null));
    }

    @Test
    @DisplayName("시나리오 입력값도 왕복한다")
    void scenarioParamsRoundTrip() {
        Map<String, Object> params = Map.of("targetVolatility", "0.1000");
        CreateBacktestCommand original =
                command(new BigDecimal("0.4000"), 63, Scenario.TARGET_RISK, params);

        assertThat(roundTrip(original, 63)).isEqualTo(original);
    }

    @Test
    @DisplayName("JSONB 가 decimal 을 문자열로 돌려줘도 같은 값으로 읽는다 — 저장 계층 설정이 계약을 좌우하지 않는다")
    void readsDecimalsStoredAsStrings() {
        CreateBacktestCommand original =
                command(new BigDecimal("0.4000"), 63, Scenario.FULL_PERIOD, null);
        Map<String, Object> spec =
                new LinkedHashMap<>(RequestSpecCodec.write(original, Scenario.FULL_PERIOD, 63));
        // MessageJsonPersistenceConfig 를 거쳐 저장됐다 돌아온 모양
        spec.put("initialCash", "10000000.00");
        List<Map<String, Object>> items = asItems(spec);
        items.get(0).put("targetWeight", "0.4000");
        spec.put("items", items);

        CreateBacktestCommand rebuilt =
                RequestSpecCodec.toCommand(spec, 7L, ExecutionType.SINGLE, COST_PROFILE);

        assertThat(rebuilt).isEqualTo(original);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asItems(Map<String, Object> spec) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) spec.get("items");
        return items.stream().map(item -> (Map<String, Object>) new LinkedHashMap<>(item)).toList();
    }

    @Test
    @DisplayName("사본이 비어 있으면 조용히 빈 요청을 만들지 않고 멈춘다")
    void refusesAnEmptySpec() {
        assertThatThrownBy(
                        () ->
                                RequestSpecCodec.toCommand(
                                        Map.of(), 7L, ExecutionType.SINGLE, COST_PROFILE))
                .isInstanceOf(IllegalStateException.class);
    }
}
