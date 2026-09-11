package com.ssafy.thorp.sector.domain.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 섹터에 연결된 대표 ETF. FR-001이 정한 항목 중 상품 쪽 다섯 가지(이름·종목 식별자·상장일·기준 지수·운용사)를 담는다.
 *
 * <p>레버리지·인버스 여부는 담지 않는다. 저장 스키마가 {@code ck_representative_etf_not_leveraged}·{@code
 * ck_representative_etf_not_inverse}로 TRUE인 행의 저장 자체를 거부하므로, 읽는 쪽에 도달한 행은 이미 조건을 만족한다. 거부 판정은 발행하는
 * 파이프라인의 몫이다(research.md 결정 7).
 *
 * <p>{@code delistedOn}도 담지 않는다. 이번 범위의 대응표는 현재 상장 상품만 다룬다(FR-015 생존 편향 안내).
 */
public record RepresentativeEtf(
        String instrumentId,
        String name,
        LocalDate listedOn,
        String benchmarkIndex,
        String manager) {

    /** {@code backtest.domain.vo.InstrumentId}와 같은 {6자리}.XKRX 형식이다(ADR-0002 D4). */
    public static final String INSTRUMENT_ID_REGEX = "^\\d{6}\\.XKRX$";

    private static final Pattern INSTRUMENT_ID_PATTERN = Pattern.compile(INSTRUMENT_ID_REGEX);

    public RepresentativeEtf {
        Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        if (!INSTRUMENT_ID_PATTERN.matcher(instrumentId).matches()) {
            throw new IllegalArgumentException("invalid instrumentId format: " + instrumentId);
        }
        requireText(name, "name");
        Objects.requireNonNull(listedOn, "listedOn must not be null");
        requireText(benchmarkIndex, "benchmarkIndex");
        requireText(manager, "manager");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank: " + value);
        }
    }
}
