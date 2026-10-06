package com.ssafy.thorp.assistant.application.result;

/**
 * 근거의 출처(spec 017 data-model §3). 서버 안과 응답에만 있고 모델에 가는 근거 줄에는 싣지 않는다.
 *
 * @param articleId 배우기 글 출처({@code TERM}·{@code ARTICLE})면 그 글의 고정 식별자, 아니면 null. 답 아래 글 연결에 쓴다
 * @param articleTitle 그 글의 게시 판 제목, 아니면 null
 */
public record EvidenceSource(EvidenceSourceType type, String articleId, String articleTitle) {

    public static EvidenceSource of(EvidenceSourceType type) {
        return new EvidenceSource(type, null, null);
    }
}
