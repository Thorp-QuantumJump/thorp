package com.ssafy.thorp.learning.application.command;

import java.util.List;

/**
 * 단계 공개(learning-admin-api §11).
 *
 * @param articles 운영자가 미리 검사에서 보고 공개하는 글과 초안 번호. 서버의 공개 집합·초안 번호와 다르면 공개하지 않는다(R23)
 * @param actorUserId 공개하는 관리 계정. 판·게시 기록·단계 공개일·변경 기록에 남는다(FR-018)
 */
public record ReleaseStageCommand(List<ExpectedArticle> articles, long actorUserId) {

    public ReleaseStageCommand {
        articles = List.copyOf(articles);
    }

    /**
     * @param articleId 형식을 검사하지 않는다. 공개 집합에 없는 값이면 목록 불일치다
     */
    public record ExpectedArticle(String articleId, int expectedDraftVersion) {}
}
