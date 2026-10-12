package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import java.util.Objects;

/**
 * 관리 화면이 읽는 초안 하나(learning-admin-api §4). 만들기·저장 응답도 같은 모양이다.
 *
 * @param article 초안 작업본과 그 글의 초안 용어
 * @param publishedRevisionNo 공개 중인 판 번호. 초안이면 null이다
 * @param publishedEdition 공개 중인 판의 종류. 초안이면 null이다
 * @param hasLegacyRevision 이 글에 9월 판(LEGACY) 판이 한 번이라도 있었는가. 게시를 취소해도 남는다. V2 초안을 V1로 되돌리는 저장은 이것이
 *     참일 때만 받으므로(research R16) 화면이 되돌리기 단추를 이 값으로 정한다
 */
public record AdminArticleResult(
        LearningArticle article,
        Integer publishedRevisionNo,
        Edition publishedEdition,
        boolean hasLegacyRevision) {

    public AdminArticleResult {
        Objects.requireNonNull(article, "article must not be null");
        if ((publishedRevisionNo == null) != (publishedEdition == null)) {
            throw new IllegalArgumentException(
                    "publishedRevisionNo and publishedEdition must be present together");
        }
    }
}
