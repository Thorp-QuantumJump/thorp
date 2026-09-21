package com.ssafy.thorp.myrecord.application.result;

import java.util.Map;

/**
 * 한 항목의 상세(spec 006 contracts "GET /my-records/{recordId}"). 목록 항목에 <b>펼쳐 보는 층</b>의 값을 더한다(원칙 II 정보
 * 세 층).
 *
 * @param referenceVersions 저장 당시 참조 버전. 004·007·010이 남긴 값을 <b>이름만 바꿔 담고</b> 값을 만들지 않는다. 원본에 없는 키는
 *     만들지 않는다 — 화면이 '기록되지 않음'을 보인다(원칙 VI). {@code worker}·{@code dependencies}는 운영 정보라 담지 않는다
 * @param mappingDrift UI-01 판정. 판정할 근거(저장 당시 대응표 버전)가 없으면 null — "안 바뀜"과 "모름"을 섞지 않는다
 * @param registryDrift 저장된 설정값이 <b>현재</b> 003 등록표 허용 범위 밖인가(FR-008). 판정은 서버가 한다 — 화면이 버전 문자열을 견주지
 *     않는다(원칙 II). 판정할 근거가 없으면 null
 * @param reopenSettings '설정 바꿔 비교'를 열 수 있는지. 경제환경 기록과 003이 다루지 않는 시나리오는 열지 않는다(research R4)
 * @param reopenPayload 저장 당시 실행 조건. 005 {@code GET /results/&#123;token&#125;}의 {@code config}와
 *     <b>같은 모양</b>이라 화면이 {@code prefillStrategySettings}에 그대로 넘긴다. {@code ANALYSIS}가 아니면 null
 */
public record MyRecordDetail(
        MyRecordView view,
        Map<String, Object> referenceVersions,
        MappingDrift mappingDrift,
        RegistryDrift registryDrift,
        ReopenSettings reopenSettings,
        Map<String, Object> reopenPayload) {

    /** '설정 바꿔 비교'를 열 수 있는가. 이유를 나누지 않는다 — 화면은 버튼을 감출지만 정한다(contracts). */
    public record ReopenSettings(boolean available) {}
}
