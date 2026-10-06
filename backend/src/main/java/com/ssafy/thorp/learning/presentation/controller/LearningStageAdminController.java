package com.ssafy.thorp.learning.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.learning.application.command.ReleaseStageCommand;
import com.ssafy.thorp.learning.application.command.ReleaseStageCommand.ExpectedArticle;
import com.ssafy.thorp.learning.application.service.LearningStageAdminService;
import com.ssafy.thorp.learning.presentation.request.StageReleaseRequest;
import com.ssafy.thorp.learning.presentation.response.AdminStagesResponse;
import com.ssafy.thorp.learning.presentation.response.StageReadinessResponse;
import com.ssafy.thorp.learning.presentation.response.StageReleaseResponse;
import com.ssafy.thorp.learning.presentation.support.LearningAdminAccess;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배우기 단계 관리 API(specs/013-learning-pages/contracts/learning-admin-api.md §9~§11). 예시는 {@code
 * docs/specs/learning-api/examples/}다.
 *
 * <p>경로가 {@code /admin/learning/**} 아래라 {@code SecurityConfig}의 {@code authenticated()}와 {@code
 * LearningAdminWebConfig}의 권한 가로채기를 그대로 받는다. 비로그인 401, 관리 권한 없음 404, 상태 변경 요청의 CSRF 불일치 403이다.
 * {@link LearningAdminController}처럼 모든 메서드가 {@link LearningAdminAccess#requireAdminUserId()}로 시작한다.
 *
 * <p>단계 공개를 되돌리는 경로는 없다(FR-031).
 */
@RestController
@RequestMapping("/admin/learning/stages")
public class LearningStageAdminController {

    private final LearningAdminAccess adminAccess;
    private final LearningStageAdminService stageService;

    public LearningStageAdminController(
            LearningAdminAccess adminAccess, LearningStageAdminService stageService) {
        this.adminAccess = adminAccess;
        this.stageService = stageService;
    }

    /** 여섯 단계와 단계마다의 글. */
    @GetMapping
    public ResponseEntity<ApiResponse<AdminStagesResponse>> listStages() {
        adminAccess.requireAdminUserId();
        return ResponseEntity.ok(ApiResponse.ok(AdminStagesResponse.from(stageService.list())));
    }

    /** 단계 미리 검사. 단계 공개와 같은 판정이며 아무것도 쓰지 않는다. */
    @GetMapping("/{stageKey}/readiness")
    public ResponseEntity<ApiResponse<StageReadinessResponse>> readiness(
            @PathVariable String stageKey) {
        adminAccess.requireAdminUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(StageReadinessResponse.from(stageService.readiness(stageKey))));
    }

    /** 단계 공개. 글 하나라도 걸리면 422이고 아무것도 바뀌지 않는다. 되돌릴 수 없다. */
    @PostMapping("/{stageKey}/release")
    public ResponseEntity<ApiResponse<StageReleaseResponse>> release(
            @PathVariable String stageKey, @Valid @RequestBody StageReleaseRequest request) {
        long actorUserId = adminAccess.requireAdminUserId();
        ReleaseStageCommand command =
                new ReleaseStageCommand(
                        request.articles().stream()
                                .map(
                                        article ->
                                                new ExpectedArticle(
                                                        article.articleId(),
                                                        article.expectedDraftVersion()))
                                .toList(),
                        actorUserId);
        return ResponseEntity.ok(
                ApiResponse.ok(StageReleaseResponse.from(stageService.release(stageKey, command))));
    }
}
