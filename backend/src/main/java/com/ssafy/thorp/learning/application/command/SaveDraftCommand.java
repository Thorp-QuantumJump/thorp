package com.ssafy.thorp.learning.application.command;

import com.ssafy.thorp.learning.domain.exception.InvalidDraftException.FieldViolation;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleSource;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.util.List;

/**
 * 초안 저장(learning-admin-api §5). 초안을 <b>통째로</b> 바꾼다. 목록에 없는 용어는 이 글의 초안에서 빠진다.
 *
 * <p><b>형식별 위반</b>: 요청 본문은 V1·V2 모양을 함께 받을 수 있게 되어 있다(예: 문단의 {@code text}와 {@code content}). 어느 형식의
 * 초안이 될지는 {@code format}이 없으면 지금 초안의 형식이라 컨트롤러가 알 수 없다. 그래서 컨트롤러는 "이 초안이 V1이면 걸리는 자리"와 "V2면 걸리는
 * 자리"를 따로 넘기고, 서비스가 형식을 정한 뒤 해당 목록으로 400을 낸다(research R16).
 *
 * @param expectedDraftVersion 화면이 마지막으로 읽은 초안 판 번호(research R12)
 * @param example 초안에서는 null을 허용한다
 * @param format 없으면 지금 초안의 형식을 그대로 둔다(9월 판 화면 호환)
 * @param stageKey 없으면 지금 초안 단계를 그대로 둔다
 * @param stagePosition 없으면 지금 단계 안 순서를 그대로 둔다
 * @param quiz 없으면 지금 초안의 문제를 그대로 둔다. 새 문제·보기는 식별자가 null이다
 * @param v1Violations 초안이 V1이면 걸리는 자리. V2 요소를 썼거나 V1에 필요한 요소가 빠졌다
 * @param v2Violations 초안이 V2면 걸리는 자리. V1 요소를 썼거나 V2에 필요한 요소가 빠졌다
 */
public record SaveDraftCommand(
        int expectedDraftVersion,
        String title,
        String question,
        int readingOrder,
        List<ArticleBlock> blocks,
        ExampleAllocation example,
        List<ArticleSource> sources,
        List<LearningTerm> terms,
        ArticleFormat format,
        StageKey stageKey,
        Integer stagePosition,
        List<QuizQuestion> quiz,
        List<FieldViolation> v1Violations,
        List<FieldViolation> v2Violations) {

    public SaveDraftCommand {
        blocks = List.copyOf(blocks);
        sources = List.copyOf(sources);
        terms = List.copyOf(terms);
        quiz = quiz == null ? null : List.copyOf(quiz);
        v1Violations = List.copyOf(v1Violations);
        v2Violations = List.copyOf(v2Violations);
    }

    /** 이 형식의 초안이면 걸리는 자리. */
    public List<FieldViolation> violationsFor(ArticleFormat format) {
        return format == ArticleFormat.V1 ? v1Violations : v2Violations;
    }
}
