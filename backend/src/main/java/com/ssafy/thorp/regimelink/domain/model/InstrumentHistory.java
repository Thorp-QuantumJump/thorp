package com.ssafy.thorp.regimelink.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 비교 묶음이 쓰는 종목 하나의 이력 (contracts §1 {@code bundle.instruments}).
 *
 * <p>ETF 이력 판정의 입력이자 화면이 "왜 이 사례를 열 수 없는지"를 설명할 근거다. {@code delistedOn}은 폐지된 종목에만 있다 — 키가 없으면 상장이
 * 유지되고 있다는 뜻이다.
 *
 * <p>{@code sectorId}는 없을 수 있다. 접수 경로를 거치지 않은 옛 실행은 종목만 남기고, 그런 실행은 대응표 판정을 건너뛴다 — 종목에서 섹터를 거꾸로 찾아
 * 채우면 그 실행이 실제로 고르지 않은 섹터를 판정에 넣게 된다.
 */
public record InstrumentHistory(
        String sectorId, String instrumentId, LocalDate listedOn, LocalDate delistedOn) {

    public InstrumentHistory {
        Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        Objects.requireNonNull(listedOn, "listedOn must not be null");
        if (delistedOn != null && !delistedOn.isAfter(listedOn)) {
            throw new IllegalArgumentException("상장폐지일은 상장일보다 뒤여야 합니다: " + instrumentId);
        }
    }

    /** 그 날짜에 거래되고 있었는가. 폐지일 당일은 이미 거래되지 않는 것으로 본다. */
    public boolean tradableOn(LocalDate date) {
        return !date.isBefore(listedOn) && (delistedOn == null || date.isBefore(delistedOn));
    }
}
