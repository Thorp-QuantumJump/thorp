package com.ssafy.thorp.learning.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.learning.application.command.CreateArticleCommand;
import com.ssafy.thorp.learning.application.command.PublishCommand;
import com.ssafy.thorp.learning.application.command.SaveDraftCommand;
import com.ssafy.thorp.learning.application.command.UnpublishCommand;
import com.ssafy.thorp.learning.application.exception.ArticleIdTakenException;
import com.ssafy.thorp.learning.application.service.LearningAdminService;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.presentation.request.CreateArticleRequest;
import com.ssafy.thorp.learning.presentation.request.PublishRequest;
import com.ssafy.thorp.learning.presentation.request.SaveDraftRequest;
import com.ssafy.thorp.learning.presentation.response.AdminAccessResponse;
import com.ssafy.thorp.learning.presentation.response.AdminArticleListResponse;
import com.ssafy.thorp.learning.presentation.response.AdminArticleResponse;
import com.ssafy.thorp.learning.presentation.response.LearningArticleResponse;
import com.ssafy.thorp.learning.presentation.response.PublishResponse;
import com.ssafy.thorp.learning.presentation.response.UnpublishResponse;
import com.ssafy.thorp.learning.presentation.support.LearningAdminAccess;
import com.ssafy.thorp.learning.presentation.support.UniqueConstraintViolations;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배우기 관리 API(specs/013-learning-pages/contracts/learning-admin-api.md). 예시는 {@code
 * docs/specs/learning-api/examples/}다.
 *
 * <p><b>모든 메서드가 {@link LearningAdminAccess#requireAdminUserId()}로 시작한다.</b> 비로그인은 {@code
 * SecurityConfig}가 먼저 401로 막고, 관리 권한이 없으면 404다. 상태 변경 요청의 CSRF는 기존 보안 설정이 403으로 막는다.
 *
 * <p>권한 판정은 {@code LearningAdminWebConfig}의 인터셉터가 요청 본문 검증보다 먼저 한 번 더 한다. 그래서 권한 없는 계정은 본문이 틀려도
 * 404를 받는다. 메서드 첫 줄의 호출은 게시 기록에 남길 사용자 식별자를 얻는 자리이기도 하다.
 */
@RestController
@RequestMapping("/admin/learning")
public class LearningAdminController {

    private final LearningAdminAccess adminAccess;
    private final LearningAdminService adminService;

    public LearningAdminController(
            LearningAdminAccess adminAccess, LearningAdminService adminService) {
        this.adminAccess = adminAccess;
        this.adminService = adminService;
    }

    /** 관리 권한 확인. 권한이 있을 때만 200이다. */
    @GetMapping("/access")
    public ResponseEntity<ApiResponse<AdminAccessResponse>> access() {
        adminAccess.requireAdminUserId();
        return ResponseEntity.ok(ApiResponse.ok(AdminAccessResponse.granted()));
    }

    /** 초안을 포함한 모든 글. */
    @GetMapping("/articles")
    public ResponseEntity<ApiResponse<AdminArticleListResponse>> listArticles() {
        adminAccess.requireAdminUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(AdminArticleListResponse.from(adminService.list())));
    }

    /** 글 만들기. 새 글은 본문이 빈 V2 초안이고 단계에 속한다. */
    @PostMapping("/articles")
    public ResponseEntity<ApiResponse<AdminArticleResponse>> createArticle(
            @Valid @RequestBody CreateArticleRequest request) {
        adminAccess.requireAdminUserId();
        CreateArticleCommand command =
                new CreateArticleCommand(
                        new ArticleId(request.articleId()),
                        request.title(),
                        new StageKey(request.stageKey()),
                        request.stagePosition());
        AdminArticleResponse created;
        try {
            created = AdminArticleResponse.from(adminService.create(command));
        } catch (DataIntegrityViolationException violation) {
            // 같은 articleId를 동시에 만든 다른 요청이 먼저 커밋했다.
            if (UniqueConstraintViolations.violates(
                    violation, UniqueConstraintViolations.ARTICLE_KEY)) {
                throw new ArticleIdTakenException();
            }
            throw violation;
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /** 초안 읽기. */
    @GetMapping("/articles/{articleId}")
    public ResponseEntity<ApiResponse<AdminArticleResponse>> getArticle(
            @PathVariable String articleId) {
        adminAccess.requireAdminUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(AdminArticleResponse.from(adminService.getDraft(articleId))));
    }

    /** 초안 저장. 공개 내용은 바뀌지 않는다. */
    @PutMapping("/articles/{articleId}")
    public ResponseEntity<ApiResponse<AdminArticleResponse>> saveDraft(
            @PathVariable String articleId, @Valid @RequestBody SaveDraftRequest request) {
        adminAccess.requireAdminUserId();
        SaveDraftCommand command = DraftCommands.toCommand(request);
        try {
            return ResponseEntity.ok(
                    ApiResponse.ok(
                            AdminArticleResponse.from(adminService.saveDraft(articleId, command))));
        } catch (DataIntegrityViolationException violation) {
            throw termConflictOr(violation, articleId, command.terms());
        }
    }

    /** 게시. 게시 전 검사에 걸리면 422이고 아무것도 바뀌지 않는다. */
    @PostMapping("/articles/{articleId}/publish")
    public ResponseEntity<ApiResponse<PublishResponse>> publish(
            @PathVariable String articleId, @Valid @RequestBody PublishRequest request) {
        long actorUserId = adminAccess.requireAdminUserId();
        try {
            return ResponseEntity.ok(
                    ApiResponse.ok(
                            PublishResponse.from(
                                    adminService.publish(
                                            articleId,
                                            new PublishCommand(
                                                    request.expectedDraftVersion(),
                                                    actorUserId)))));
        } catch (DataIntegrityViolationException violation) {
            List<LearningTerm> terms = adminService.getDraft(articleId).article().terms();
            throw termConflictOr(violation, articleId, terms);
        }
    }

    /** 미리 보기. 지금 초안을 독자 화면과 같은 모양으로 돌려준다(§8). 검사하지 않고 아무것도 쓰지 않는다. 공개 조회와 나의 학습은 바뀌지 않는다. */
    @GetMapping("/articles/{articleId}/preview")
    public ResponseEntity<ApiResponse<LearningArticleResponse>> preview(
            @PathVariable String articleId) {
        adminAccess.requireAdminUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(LearningArticleResponse.from(adminService.preview(articleId))));
    }

    /** 게시 취소. 본문이 없다. 이미 초안이면 아무것도 바꾸지 않고 200이다. */
    @PostMapping("/articles/{articleId}/unpublish")
    public ResponseEntity<ApiResponse<UnpublishResponse>> unpublish(
            @PathVariable String articleId) {
        long actorUserId = adminAccess.requireAdminUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(
                        UnpublishResponse.from(
                                adminService.unpublish(
                                        articleId, new UnpublishCommand(actorUserId)))));
    }

    /**
     * 용어 식별자의 UNIQUE 제약 위반이면 어느 글이 가져갔는지(넘겨받는 중인지) 새로 읽어 409로 바꾼다. 그 사이 먼저 커밋한 요청이 되돌려져 겹치는 글이 없으면
     * 원래 예외를 그대로 둔다. 판정할 수 없는 충돌을 409로 꾸며 내지 않는다.
     */
    private RuntimeException termConflictOr(
            DataIntegrityViolationException violation, String articleId, List<LearningTerm> terms) {
        if (UniqueConstraintViolations.violatesTermKey(violation)) {
            adminService.requireTermIdsAvailable(articleId, terms);
        }
        return violation;
    }
}
