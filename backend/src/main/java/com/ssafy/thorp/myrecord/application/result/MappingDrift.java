package com.ssafy.thorp.myrecord.application.result;

import java.util.List;

/**
 * UI-01 판정 결과(spec 006 research R8). 저장 당시 대응표와 현재 대응표의 <b>항목</b>을 비교한 것이다.
 *
 * <p>버전 문자열이 달라도 이 기록이 쓴 섹터의 대표 ETF가 같으면 {@code changed=false}다. 대응표는 다른 섹터 때문에도 새 버전이 나오므로 버전만 보면
 * 내 섹터가 그대로인데도 경고가 뜬다 — 원칙 II가 금지한 "결과가 바뀌지 않았는데 늘어나는 경고"다.
 *
 * @param kind 바뀐 종류. {@code changed=false}면 null
 * @param sectorCodes 영향을 받은 섹터. {@code changed=false}면 빈 목록
 * @param message 화면에 그대로 보이는 한 문장. {@code changed=false}면 null
 */
public record MappingDrift(boolean changed, Kind kind, List<String> sectorCodes, String message) {

    public enum Kind {
        /** 섹터는 남았는데 대표 ETF가 다른 종목으로 바뀌었다. */
        INSTRUMENT_REPLACED,
        /** 섹터 자체가 현재 대응표에서 빠졌다. '설정 바꿔 비교'에서 대체 섹터를 고르게 하되 자동으로 바꾸지 않는다(FR-008). */
        SECTOR_REMOVED
    }

    public static MappingDrift none() {
        return new MappingDrift(false, null, List.of(), null);
    }
}
