package com.ssafy.thorp.myrecord.application.port;

import java.util.List;
import java.util.Optional;

/**
 * 섹터-ETF 대응표의 항목을 버전으로 읽는 통로(spec 006 research R8).
 *
 * <p>001의 {@code SectorRepository}는 <b>지금 열린</b> 버전만 읽는다. UI-01 판정은 저장 당시 버전의 항목도 필요하므로 006이 자기 포트를
 * 둔다. 읽기만 한다 — 대응표의 원본은 자료 파이프라인이다.
 */
public interface SectorMapEntries {

    /**
     * 그 버전의 항목. 버전 행이 없으면 빈 값이다 — 항목 0개와 구분해야 "판정 불가"를 말할 수 있다.
     *
     * @param mapVersion {@code sector_etf_map_version.version}
     */
    Optional<List<Entry>> entriesOf(String mapVersion);

    /** 지금 열린 버전({@code effective_to IS NULL})의 항목. 열린 버전이 없으면 빈 값. */
    Optional<List<Entry>> currentEntries();

    /**
     * @param sectorName 화면 문장에 쓴다. 사용자는 코드가 아니라 이름으로 섹터를 안다
     * @param etfId {@code representative_etf.id}. 대표 ETF가 바뀌었는지는 이 값으로 본다
     */
    record Entry(String sectorCode, String sectorName, long etfId) {}
}
