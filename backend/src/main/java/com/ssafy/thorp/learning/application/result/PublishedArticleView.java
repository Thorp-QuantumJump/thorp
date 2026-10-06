package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.Placement;
import java.util.Objects;

/**
 * 공개 중인 글 하나의 공개 상태. 판과 글 행의 공개 배치를 함께 읽는다. 판에는 단계가 없고 공개 배치가 갖는다(research R15).
 *
 * @param revision 공개 중인 판
 * @param placement 공개 배치. 게시 중인 글이면 늘 있다(테이블 CHECK)
 */
public record PublishedArticleView(ArticleRevision revision, Placement placement) {

    public PublishedArticleView {
        Objects.requireNonNull(revision, "revision must not be null");
        Objects.requireNonNull(placement, "placement must not be null");
    }
}
