package com.ssafy.thorp.regime.domain.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 사례 하나의 비슷한 점·다른 점 (spec FR-015, research R8, spec 010 v3 개정안 §3.5·§4).
 *
 * <p><b>배치가 템플릿으로 조립해 저장한 문장을 그대로 낸다.</b> 여기서 문장을 만들거나 고르지 않는다 — 조회가 문구를 손보기 시작하면 같은 결과가 화면마다 다른 말을
 * 하게 된다.
 *
 * <p>{@code unusual}은 블록 안 엇갈림(잔차)이 컸던 사례다. {@code queryNote}는 지금 시점의 같은 안내이며 없으면 {@code null}이다.
 * {@code context}는 특이 사례의 관측 구간 안 공식 발간물·사건 링크이며 검색 입력이 아니다.
 */
public record CaseExplanation(
        List<String> similar,
        List<String> different,
        boolean unusual,
        String queryNote,
        List<ContextLink> context) {

    /** 당시 맥락 한 건 — 제목·날짜·출처·원문 링크만 있다. {@code url}이 없는 사건도 있다(경기 정점·저점). */
    public record ContextLink(
            String kind, LocalDate date, String title, String url, String source) {}
}
