package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.Objects;

/**
 * 단계 관리가 글마다 보는 값(learning-admin-api §9~§11). 본문·용어·퀴즈를 읽지 않는다. 단계 목록과 공개 집합을 정할 때 쓰고, 검사가 필요한 글만
 * 따로 전부 읽는다.
 *
 * @param publishedEdition 공개 중인 판의 종류. 게시되지 않았으면 null이다
 * @param placement 초안 배치
 * @param publishedPlacement 공개 배치. 게시되지 않았으면 null이다
 */
public record StageArticleEntry(
        ArticleId articleId,
        String title,
        ArticleFormat format,
        ArticleStatus status,
        Edition publishedEdition,
        int draftVersion,
        Placement placement,
        Placement publishedPlacement) {

    public StageArticleEntry {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(placement, "placement must not be null");
    }
}
