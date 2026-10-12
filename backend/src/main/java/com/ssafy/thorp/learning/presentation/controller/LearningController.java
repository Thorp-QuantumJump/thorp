package com.ssafy.thorp.learning.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.learning.application.service.LearningQueryService;
import com.ssafy.thorp.learning.presentation.response.CurriculumResponse;
import com.ssafy.thorp.learning.presentation.response.LearningArticleResponse;
import com.ssafy.thorp.learning.presentation.response.LearningTermResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배우기 공개 조회 API(specs/013-learning-pages/contracts/learning-public-api.md). 예시는 {@code
 * docs/specs/learning-api/examples/}다.
 *
 * <p><b>비로그인을 허용한다</b>(계약 머리말, FR-012). {@code SecurityConfig}가 이 경로를 {@code
 * anyRequest().permitAll()}로 두므로 추가 설정이 없다. 사용자 식별을 읽지 않고 조회를 사용자별로 남기지 않는다.
 *
 * <p>여러 용어를 한 번에 받는 조회는 두지 않는다(계약 §5, research R10).
 */
@RestController
@RequestMapping("/learning")
public class LearningController {

    private final LearningQueryService learningQueryService;

    public LearningController(LearningQueryService learningQueryService) {
        this.learningQueryService = learningQueryService;
    }

    /** 커리큘럼. 여섯 단계와 공개 상태, 공개된 글, 변경 기록이다. 게시된 글이 없어도 200이다. */
    @GetMapping("/curriculum")
    public ResponseEntity<ApiResponse<CurriculumResponse>> curriculum() {
        return ResponseEntity.ok(
                ApiResponse.ok(CurriculumResponse.from(learningQueryService.curriculum())));
    }

    /** 공개 중인 글 하나. 볼 수 없는 글은 404 {@code LEARNING_ARTICLE_UNAVAILABLE}이다. */
    @GetMapping("/articles/{articleId}")
    public ResponseEntity<ApiResponse<LearningArticleResponse>> getArticle(
            @PathVariable String articleId) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        LearningArticleResponse.from(
                                learningQueryService.getPublished(articleId))));
    }

    /** 진단 화면 도움말이 쓰는 용어 설명 하나. 게시된 설명이 없으면 404 {@code LEARNING_TERM_NOT_READY}다. */
    @GetMapping("/terms/{termId}")
    public ResponseEntity<ApiResponse<LearningTermResponse>> getTerm(@PathVariable String termId) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        LearningTermResponse.from(learningQueryService.getPublishedTerm(termId))));
    }
}
