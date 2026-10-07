package com.ssafy.thorp.learning.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.learning.application.service.LearnerService;
import com.ssafy.thorp.learning.presentation.request.AnswerRequest;
import com.ssafy.thorp.learning.presentation.request.PositionRequest;
import com.ssafy.thorp.learning.presentation.response.AnswerResponse;
import com.ssafy.thorp.learning.presentation.response.CompletionResponse;
import com.ssafy.thorp.learning.presentation.response.LearnerArticleResponse;
import com.ssafy.thorp.learning.presentation.response.LearnerOverviewResponse;
import com.ssafy.thorp.learning.presentation.response.RetryResponse;
import com.ssafy.thorp.learning.presentation.support.LearnerAccess;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 나의 학습 API(specs/013-learning-pages/contracts/learning-me-api.md). 예시는 {@code
 * docs/specs/learning-api/examples/me*.json}이다.
 *
 * <p><b>로그인 사용자만 쓴다.</b> {@code SecurityConfig}가 {@code /learning/me/**}를 {@code authenticated()}로
 * 묶고, 상태 변경은 CSRF 헤더를 요구한다. 모든 요청은 로그인 주체의 기록만 다루며 다른 사용자의 기록을 가리키는 경로나 매개변수가 없다(§0).
 */
@RestController
@RequestMapping("/learning/me")
public class LearnerController {

    private final LearnerService learnerService;
    private final LearnerAccess learnerAccess;

    public LearnerController(LearnerService learnerService, LearnerAccess learnerAccess) {
        this.learnerService = learnerService;
        this.learnerAccess = learnerAccess;
    }

    /** 나의 학습 전체. 요청마다 정산한다. 첫 단계 공개 전에는 409 {@code LEARNING_NOT_RECORDABLE}이다. */
    @GetMapping
    public ResponseEntity<ApiResponse<LearnerOverviewResponse>> overview() {
        long userId = learnerAccess.requireUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(LearnerOverviewResponse.from(learnerService.overview(userId))));
    }

    /** 학습 초기화. 기록이 하나도 없어도 204다. */
    @DeleteMapping
    public ResponseEntity<Void> reset() {
        long userId = learnerAccess.requireUserId();
        learnerService.reset(userId);
        return ResponseEntity.noContent().build();
    }

    /** 글 하나의 기록. 정산하지 않는다. */
    @GetMapping("/articles/{articleId}")
    public ResponseEntity<ApiResponse<LearnerArticleResponse>> article(
            @PathVariable String articleId) {
        long userId = learnerAccess.requireUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(
                        LearnerArticleResponse.from(learnerService.article(userId, articleId))));
    }

    /** 읽은 위치. 화면은 글을 떠날 때마다 한 번 보낸다. */
    @PutMapping("/articles/{articleId}/position")
    public ResponseEntity<Void> position(
            @PathVariable String articleId, @Valid @RequestBody PositionRequest request) {
        long userId = learnerAccess.requireUserId();
        learnerService.recordPosition(userId, articleId, request.percent());
        return ResponseEntity.noContent().build();
    }

    /** 완료. 이미 완료한 글이면 그때의 시각과 빈 {@code gained}로 200이다. */
    @PostMapping("/articles/{articleId}/completion")
    public ResponseEntity<ApiResponse<CompletionResponse>> complete(
            @PathVariable String articleId) {
        long userId = learnerAccess.requireUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(
                        CompletionResponse.from(learnerService.complete(userId, articleId))));
    }

    /** 첫 답. 이미 답한 문제면 저장된 첫 답을 {@code alreadyAnswered: true}로 돌려준다. */
    @PostMapping("/articles/{articleId}/answers")
    public ResponseEntity<ApiResponse<AnswerResponse>> answer(
            @PathVariable String articleId, @Valid @RequestBody AnswerRequest request) {
        long userId = learnerAccess.requireUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(
                        AnswerResponse.from(
                                learnerService.answer(
                                        userId,
                                        articleId,
                                        request.questionId(),
                                        request.choiceId()))));
    }

    /** 다시 풀기. 답을 저장하지 않는다. */
    @PostMapping("/articles/{articleId}/retry-answers")
    public ResponseEntity<ApiResponse<RetryResponse>> retry(
            @PathVariable String articleId, @Valid @RequestBody AnswerRequest request) {
        long userId = learnerAccess.requireUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(
                        RetryResponse.from(
                                learnerService.retry(
                                        userId,
                                        articleId,
                                        request.questionId(),
                                        request.choiceId()))));
    }
}
