package com.ssafy.thorp.learning.application.command;

import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.util.Objects;

/**
 * 글 만들기(learning-admin-api §3). 새 글은 본문이 빈 V2 초안이고 단계에 속한다.
 *
 * @param stagePosition 단계 안 순서 1..99
 */
public record CreateArticleCommand(
        ArticleId articleId, String title, StageKey stageKey, int stagePosition) {

    public CreateArticleCommand {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(stageKey, "stageKey must not be null");
    }

    public Placement placement() {
        return new Placement(stageKey, stagePosition);
    }
}
