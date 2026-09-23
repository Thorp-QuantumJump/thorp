package com.ssafy.thorp.backtest.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 동일 버전 키(spec 004 research R-03). canonical JSON → SHA-256 hex 64자.
 *
 * <p>이 키가 같으면 <b>재계산 결과가 같다</b>고 선언하는 것이고, 그 선언 위에서 진행 중 실행에 합류하거나(JOINED) 끝난 결과를 재사용한다(REUSED).
 * 그러므로 결과 숫자를 바꿀 수 있는 값은 빠짐없이 들어가야 하고, 바꿀 수 없는 값은 들어가면 안 된다(들어가면 같은 계산을 쓸데없이 두 번 한다).
 *
 * <p><b>일부러 뺀 것</b>: 요청자와 {@code executionType}. 누가 요청했는지는 숫자를 바꾸지 않는다(R-03). 결과는 서비스 전체가
 * 공유한다(Clarify Q2).
 *
 * <p><b>빠지기 쉬운데 넣어야 하는 것</b>: 실효 워밍업 거래일 수. 같은 전략·같은 기간이라도 비교 묶음이 다르면 워밍업이 달라지고(R-15) 그러면 {@code
 * effectiveStart}가 달라져 숫자가 달라진다. 키에 없으면 두 실행이 한 키로 묶여 잘못된 결과를 재사용한다.
 */
@Component
public class DedupKeyCalculator {

    /**
     * 키 정렬만 켠 평범한 Mapper.
     *
     * <p>메시지 ObjectMapper를 빌려 쓰지 않는다 — 그것은 infrastructure에 있고 application이 참조할 수 없다(ArchUnit 규칙).
     * 대신 소수·날짜를 <b>맵에 넣기 전에</b> 문자열로 정규화하므로 Mapper 설정에 표기가 좌우되지 않는다. 직렬화 설정에 기대면 그 설정이 바뀐 날 모든 키가
     * 조용히 바뀌고, 그러면 진행 중이던 재사용 판정이 전부 어긋난다.
     */
    private final ObjectMapper canonical =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * 키 계산 입력. 전부 서버가 확정한 값이다 — 요청 원문이 아니라 해석된 값을 넣는다.
     *
     * @param warmupTradingDays 서버가 확정한 실효 워밍업 거래일 수(R-15)
     * @param scenarioParams 시나리오 입력값. 없으면 null
     * @param scenarioRulesVersion 시나리오 오버레이 규칙 버전. TARGET_RISK 에서만 채운다
     */
    public record Input(
            String strategyId,
            String rulesVersion,
            Map<String, Object> parameters,
            String mappingVersion,
            List<UniverseEntry> universe,
            LocalDate start,
            LocalDate end,
            int warmupTradingDays,
            BigDecimal initialCash,
            String dataVersion,
            String evaluationSplitVersion,
            String costProfileVersion,
            String engineVersion,
            String scenario,
            Map<String, Object> scenarioParams,
            String scenarioRulesVersion) {}

    /**
     * @param targetWeight FIXED_WEIGHT 에서만 존재. 그 외 null
     */
    public record UniverseEntry(String sectorId, String instrumentId, BigDecimal targetWeight) {}

    public String calculate(Input input) {
        return sha256Hex(canonicalJson(input));
    }

    /** 테스트와 장애 분석에서 "왜 키가 다른가"를 보려면 해시 이전 문자열이 필요하다. */
    public String canonicalJson(Input input) {
        Map<String, Object> root = new TreeMap<>();
        put(root, "strategyId", input.strategyId());
        put(root, "rulesVersion", input.rulesVersion());
        put(root, "parameters", input.parameters());
        put(root, "mappingVersion", input.mappingVersion());
        put(root, "universe", universe(input.universe()));
        put(root, "start", input.start());
        put(root, "end", input.end());
        put(root, "warmupTradingDays", input.warmupTradingDays());
        put(root, "initialCash", input.initialCash());
        put(root, "dataVersion", input.dataVersion());
        put(root, "evaluationSplitVersion", input.evaluationSplitVersion());
        put(root, "costProfileVersion", input.costProfileVersion());
        put(root, "engineVersion", input.engineVersion());
        put(root, "scenario", input.scenario());
        put(root, "scenarioParams", input.scenarioParams());
        put(root, "scenarioRulesVersion", input.scenarioRulesVersion());
        try {
            return canonical.writeValueAsString(root);
        } catch (JsonProcessingException exc) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "동일 버전 키를 만들 수 없습니다: " + exc.getOriginalMessage());
        }
    }

    /** null 키는 넣지 않는다 — "키 없음"과 "값이 null"이 같은 키를 내야 한다. */
    private static void put(Map<String, Object> target, String key, Object value) {
        Object normalized = normalize(value);
        if (normalized != null) {
            target.put(key, normalized);
        }
    }

    /**
     * 소수와 날짜를 문자열로 고정한다.
     *
     * <p>소수는 {@code stripTrailingZeros().toPlainString()}이다. 화면이 {@code "0.40"}을 보내든 {@code
     * "0.4000"}을 보내든 같은 비중이므로 같은 키여야 한다. 지수 표기는 쓰지 않는다.
     */
    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return sortedCopy(map);
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            list.forEach(item -> items.add(normalize(item)));
            return items;
        }
        return value;
    }

    private static Map<String, Object> sortedCopy(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Map<String, Object> sorted = new TreeMap<>();
        source.forEach((key, value) -> put(sorted, String.valueOf(key), value));
        return sorted;
    }

    /** 종목 목록은 요청 순서가 아니라 (섹터, 종목) 순으로 정렬한다 — 화면이 순서를 바꿔도 같은 실행이다. */
    private static List<Map<String, Object>> universe(List<UniverseEntry> entries) {
        List<UniverseEntry> sorted = new ArrayList<>(entries);
        sorted.sort(
                Comparator.comparing(
                                UniverseEntry::sectorId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(UniverseEntry::instrumentId));
        List<Map<String, Object>> items = new ArrayList<>(sorted.size());
        for (UniverseEntry entry : sorted) {
            Map<String, Object> item = new TreeMap<>();
            put(item, "sectorId", entry.sectorId());
            put(item, "instrumentId", entry.instrumentId());
            put(item, "targetWeight", entry.targetWeight());
            items.add(item);
        }
        return items;
    }

    private static String sha256Hex(String canonicalJson) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exc);
        }
    }
}
