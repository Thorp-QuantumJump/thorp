package com.ssafy.thorp.learning.application.command;

import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleSource;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import java.util.List;

/**
 * 초안 저장(learning-admin-api §5). 초안을 <b>통째로</b> 바꾼다. 목록에 없는 용어는 이 글의 초안에서 빠진다.
 *
 * @param expectedDraftVersion 화면이 마지막으로 읽은 초안 판 번호(research R12)
 * @param example 초안에서는 null을 허용한다
 */
public record SaveDraftCommand(
        int expectedDraftVersion,
        String title,
        String question,
        int readingOrder,
        List<ArticleBlock> blocks,
        ExampleAllocation example,
        List<ArticleSource> sources,
        List<LearningTerm> terms) {

    public SaveDraftCommand {
        blocks = List.copyOf(blocks);
        sources = List.copyOf(sources);
        terms = List.copyOf(terms);
    }
}
