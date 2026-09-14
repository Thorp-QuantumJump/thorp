package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 접수한 요청의 canonical 사본({@code backtest_run.request_spec})을 쓰고 다시 읽는다.
 *
 * <p><b>쓰는 쪽과 읽는 쪽을 한 클래스에 둔다.</b> 접수(T005)가 쓰고 재시도(T010)가 읽는데, 둘이 멀리 떨어져 있으면 한쪽만 고쳤을 때 재시도가 조용히
 * 깨진다 — 사본의 모양이 계약인 셈이다. {@code RequestSpecCodecTest}가 왕복을 단언해 그 드리프트를 빌드에서 잡는다.
 *
 * <p><b>수를 읽을 때 문자열과 숫자를 모두 받는다.</b> 이 사본은 JSONB 컬럼이고 직렬화를 메시지 ObjectMapper가 맡아 decimal이 문자열로
 * 저장되지만({@code MessageJsonPersistenceConfig}), 그 설정은 저장 계층의 사정이지 이 사본의 계약이 아니다. 한쪽만 받으면 설정이 바뀐 날
 * 재시도가 전부 깨진다.
 *
 * <p>사본에 <b>없는</b> 값 셋은 호출자가 준다 — 요청자({@code requesterId})와 {@code executionType}은 요청 행의 것이라 실행 사본에
 * 들어가지 않고(동일 버전 키에서도 일부러 뺀다), 비용 조건 버전은 실행 계획({@code RunPlan})에 따로 있다.
 */
final class RequestSpecCodec {

    private RequestSpecCodec() {}

    /**
     * 접수가 확정한 값으로 사본을 만든다. {@code warmupTradingDays}는 요청값이 아니라 <b>실효값</b>이다 — 요청이 비워 둔 경우에도 그때 무엇으로
     * 계산했는지가 남아야 재시도가 같은 구간을 본다.
     */
    static Map<String, Object> write(
            CreateBacktestCommand command, Scenario scenario, int warmupTradingDays) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (CreateBacktestCommand.AllocationItem item : command.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sectorId", item.sectorId());
            entry.put("instrumentId", item.instrumentId());
            if (item.targetWeight() != null) {
                entry.put("targetWeight", item.targetWeight());
            }
            items.add(entry);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mappingVersion", command.mappingVersion());
        spec.put("items", items);
        spec.put("strategyId", command.strategyId());
        spec.put("rulesVersion", command.rulesVersion());
        if (command.registryVersion() != null) {
            spec.put("registryVersion", command.registryVersion());
        }
        spec.put("parameters", command.parameters());
        spec.put("start", command.start().toString());
        spec.put("end", command.end().toString());
        spec.put("initialCash", command.initialCash());
        if (command.evaluationSplitVersion() != null) {
            spec.put("evaluationSplitVersion", command.evaluationSplitVersion());
        }
        spec.put("warmupTradingDays", warmupTradingDays);
        spec.put("scenario", scenario.name());
        if (command.scenarioParams() != null && !command.scenarioParams().isEmpty()) {
            spec.put("scenarioParams", command.scenarioParams());
        }
        return spec;
    }

    /**
     * 사본을 접수 명령으로 되돌린다. 재시도는 이 명령으로 접수를 <b>다시</b> 탄다 — 그래야 그 사이 전략 등록표가 내려갔거나 자료 버전이 움직인 것을 지금 기준으로
     * 다시 판정한다.
     *
     * @param requesterId 재시도를 누른 사람. 원 요청자와 같다(관문이 먼저 확인한다)
     */
    static CreateBacktestCommand toCommand(
            Map<String, Object> spec,
            long requesterId,
            ExecutionType executionType,
            String costProfileVersion) {
        if (spec == null || spec.isEmpty()) {
            throw new IllegalStateException("request_spec is empty; cannot rebuild the request");
        }
        return new CreateBacktestCommand(
                requesterId,
                executionType,
                string(spec, "mappingVersion"),
                items(spec),
                string(spec, "strategyId"),
                string(spec, "rulesVersion"),
                string(spec, "registryVersion"),
                map(spec, "parameters"),
                date(spec, "start"),
                date(spec, "end"),
                decimal(spec.get("initialCash"), "initialCash"),
                costProfileVersion,
                string(spec, "evaluationSplitVersion"),
                integer(spec, "warmupTradingDays"),
                Scenario.valueOf(string(spec, "scenario")),
                map(spec, "scenarioParams"));
    }

    @SuppressWarnings("unchecked")
    private static List<CreateBacktestCommand.AllocationItem> items(Map<String, Object> spec) {
        Object raw = spec.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("request_spec.items is missing or empty");
        }
        List<CreateBacktestCommand.AllocationItem> items = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> entry = (Map<String, Object>) element;
            items.add(
                    new CreateBacktestCommand.AllocationItem(
                            string(entry, "sectorId"),
                            string(entry, "instrumentId"),
                            entry.get("targetWeight") == null
                                    ? null
                                    : decimal(entry.get("targetWeight"), "items[].targetWeight")));
        }
        return items;
    }

    private static String string(Map<String, Object> spec, String key) {
        Object value = spec.get(key);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> spec, String key) {
        Object value = spec.get(key);
        return value == null ? null : (Map<String, Object>) value;
    }

    private static LocalDate date(Map<String, Object> spec, String key) {
        String value = string(spec, key);
        if (value == null) {
            throw new IllegalStateException("request_spec." + key + " is missing");
        }
        return LocalDate.parse(value);
    }

    private static Integer integer(Map<String, Object> spec, String key) {
        Object value = spec.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    /** JSONB 에 문자열로 저장되든 숫자로 저장되든 같은 값을 돌려준다. */
    private static BigDecimal decimal(Object value, String path) {
        if (value == null) {
            throw new IllegalStateException("request_spec." + path + " is missing");
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(value.toString());
    }
}
