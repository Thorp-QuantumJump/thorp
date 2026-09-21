package com.ssafy.thorp.myrecord.application.result;

import java.util.Map;

/**
 * 한 항목의 상세(spec 006 contracts "GET /my-records/{recordId}"). 목록 항목에 <b>펼쳐 보는 층</b>의 값을 더한다(원칙 II 정보
 * 세 층).
 *
 * @param referenceVersions 저장 당시 참조 버전. 004·007·010이 남긴 값을 <b>이름만 바꿔 담고</b> 값을 만들지 않는다. 원본에 없는 키는
 *     만들지 않는다 — 화면이 '기록되지 않음'을 보인다(원칙 VI). {@code worker}·{@code dependencies}는 운영 정보라 담지 않는다
 * @param mappingDrift UI-01 판정. 판정할 근거(저장 당시 대응표 버전)가 없으면 null — "안 바뀜"과 "모름"을 섞지 않는다
 */
public record MyRecordDetail(
        MyRecordView view, Map<String, Object> referenceVersions, MappingDrift mappingDrift) {}
