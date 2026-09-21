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
     * 저장 당시 버전과 지금 열린 버전({@code effective_to IS NULL})의 항목을 한 번에 읽는다.
     *
     * @return 두 버전 행 중 하나라도 없으면 빈 값 — 항목 0개와 구분해야 "판정 불가"를 말할 수 있다. 두 버전이 같은 행이면 {@code
     *     same=true}이고 항목은 읽지 않는다(바뀔 수 없다)
     */
    Optional<Versions> load(String savedMapVersion);

    /**
     * @param sectorName 화면 문장에 쓴다. 사용자는 코드가 아니라 이름으로 섹터를 안다
     * @param etfId {@code representative_etf.id}. 대표 ETF가 바뀌었는지는 이 값으로 본다
     */
    record Entry(String sectorCode, String sectorName, long etfId) {}

    /**
     * @param same 저장 버전이 곧 열린 버전이다. 그러면 {@code saved}·{@code current}는 비어 있다
     */
    record Versions(boolean same, List<Entry> saved, List<Entry> current) {}
}
