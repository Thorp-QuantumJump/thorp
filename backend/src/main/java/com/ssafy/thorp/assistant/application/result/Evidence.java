package com.ssafy.thorp.assistant.application.result;

import java.time.LocalDate;

/**
 * 근거 하나(spec 015 Key Entities).
 *
 * @param id 요청 안에서만 뜻이 있는 {@code Fn}. 요청마다 새로 매겨 결과 필드 이름을 노출하지 않는다(FR-008). 팩에 넣기 전에는 null일 수 있다
 * @param label 사람이 읽는 이름. 계약 §2.1 포함 표의 한국어 고정 문자열이지 필드명이 아니다
 * @param value 서버가 읽은 값의 원문 문자열. 모델에는 이 원문이 가고, 화면에는 {@link #formattedValue()}가 간다
 * @param asOf 기준 날짜. 없을 수 있다
 * @param tab 그 값이 있는 결과 탭 id
 * @param kind 표기 함수
 */
public record Evidence(
        String id, String label, String value, LocalDate asOf, String tab, EvidenceKind kind) {

    public Evidence withId(String newId) {
        return new Evidence(newId, label, value, asOf, tab, kind);
    }

    public String formattedValue() {
        return (kind == null ? EvidenceKind.TEXT : kind).format(value);
    }
}
