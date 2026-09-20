package com.ssafy.thorp.regimelink.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.domain.model.FilterDefinition;
import com.ssafy.thorp.regimelink.domain.model.InstrumentHistory;
import com.ssafy.thorp.regimelink.domain.vo.FilterKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 가용성 필터의 동일성을 정하는 해시 (research R4).
 *
 * <p>같은 비교 묶음·같은 자료면 같은 값이 나와야 "같은 비교 묶음은 같은 후보"(FR-021)가 키 일치로 검증된다.
 *
 * <p><b>날짜를 맵에 넣기 전에 문자열로 정규화한다.</b> 표기를 직렬화 설정에 맡기면 그 설정이 바뀐 날 모든 키가 조용히 바뀌고, 그러면 저장된 필터가 전부 다시
 * 만들어진다(004 {@code DedupKeyCalculator}와 같은 이유).
 *
 * <p><b>정의에 성과가 들어가지 않는다.</b> 기존 실행의 분석 기간도 넣지 않는다 — 기간은 {@code OUT_OF_PERIOD} 판정에만 쓰이고 필터 조건이
 * 아니라서, 넣으면 같은 묶음이 기간만 다른 두 키를 갖는다(FR-003).
 */
@Component
public class FilterKeyCalculator {

    private final ObjectMapper canonical =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public FilterKey calculate(FilterDefinition definition) {
        return new FilterKey(sha256(canonicalJson(definition)));
    }

    /** 저장 컬럼({@code regime_link_filter.definition})에 그대로 들어가는 모양이기도 하다. */
    public Map<String, Object> asMap(FilterDefinition definition) {
        Map<String, Object> map = new TreeMap<>();
        map.put("instruments", instruments(definition.instruments()));
        map.put("maxWarmupTradingDays", definition.maxWarmupTradingDays());
        map.put("minPostTradingDays", definition.minPostTradingDays());
        map.put("mappingVersion", definition.mappingVersion());
        map.put("dataVersion", definition.dataVersion());
        map.put("dataAsOf", definition.dataAsOf().toString());
        map.put("postCutoff", definition.postCutoff().toString());
        return map;
    }

    /**
     * 종목은 {@code instrumentId}·{@code listedOn} 둘만 담는다.
     *
     * <p>섹터와 상장폐지일은 판정에 쓰지만 키에 넣지 않는다 — 대응표 버전이 이미 키에 있어 섹터 연결이 바뀌면 키가 바뀌고, 폐지일은 자료 버전에 딸린 값이라 따로
     * 세면 같은 자료에서 두 키가 나온다.
     */
    private static List<Map<String, Object>> instruments(List<InstrumentHistory> instruments) {
        List<Map<String, Object>> rows = new ArrayList<>(instruments.size());
        for (InstrumentHistory instrument : instruments) {
            Map<String, Object> row = new TreeMap<>();
            row.put("instrumentId", instrument.instrumentId());
            row.put("listedOn", instrument.listedOn().toString());
            rows.add(row);
        }
        return rows;
    }

    private String canonicalJson(FilterDefinition definition) {
        try {
            return canonical.writeValueAsString(asMap(definition));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "필터 키를 만들지 못했습니다.");
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
        }
    }
}
