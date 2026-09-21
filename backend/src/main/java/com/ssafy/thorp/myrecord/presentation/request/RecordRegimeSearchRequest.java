package com.ssafy.thorp.myrecord.presentation.request;

import com.ssafy.thorp.myrecord.domain.vo.EntryPath;
import com.ssafy.thorp.regime.domain.vo.BaselineOrigin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 경제환경 검색을 개인 기록으로 남기는 요청(contracts "POST /my-records/regime-searches").
 *
 * <p><b>세 값 모두 화면이 보낸다.</b> 진입 경로는 조회한 화면만 알고(010 FR-029), 기준월 근거는 010의 식별 키에 들어 있지 않아 {@code
 * searchId}만으로는 되짚을 수 없다 — 서버가 추정하면 원칙 VI 위반이라 빠지면 400이다(research R15).
 *
 * <p>값의 목록을 문자열로 받아 서버가 가르지 않고 enum 으로 받는다. 모르는 값은 본문을 읽는 단계에서 {@code 400 INVALID_INPUT}이 되고, 값이
 * 늘어날 때 검사할 자리가 한 곳으로 남는다.
 *
 * @param searchId 010이 응답한 스냅샷 식별 키({@code RegimeSearchResult.searchId}). 없는 키는 404다
 * @param baselineOrigin 010 응답의 값을 그대로 넘긴다
 */
public record RecordRegimeSearchRequest(
        @NotBlank(message = "searchId 는 필수입니다") String searchId,
        @NotNull(message = "entryPath 는 필수입니다") EntryPath entryPath,
        @NotNull(message = "baselineOrigin 은 필수입니다") BaselineOrigin baselineOrigin) {}
