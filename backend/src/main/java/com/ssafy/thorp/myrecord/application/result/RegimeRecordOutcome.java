package com.ssafy.thorp.myrecord.application.result;

/**
 * 경제환경 기록 요청의 결과(spec 006 T035, contracts "POST /my-records/regime-searches").
 *
 * <p>본문은 목록 항목과 같으므로 여기서는 <b>어느 항목인지</b>와 <b>새로 만들었는지</b>만 나른다 — 응답 본문은 컨트롤러가 목록과 같은 읽기 경로로 짓고,
 * {@code created}가 201과 200을 가른다.
 *
 * @param created 이 요청이 행을 만들었는가. 거짓이면 이미 있던 항목의 열람 시각만 올렸다는 뜻이다
 */
public record RegimeRecordOutcome(String recordId, boolean created) {}
