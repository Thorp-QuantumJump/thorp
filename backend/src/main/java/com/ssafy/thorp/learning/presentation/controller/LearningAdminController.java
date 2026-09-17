package com.ssafy.thorp.learning.presentation.controller;

import com.ssafy.thorp.common.web.response.ApiResponse;
import com.ssafy.thorp.learning.application.command.CreateArticleCommand;
import com.ssafy.thorp.learning.application.command.PublishCommand;
import com.ssafy.thorp.learning.application.command.SaveDraftCommand;
import com.ssafy.thorp.learning.application.command.UnpublishCommand;
import com.ssafy.thorp.learning.application.service.LearningAdminService;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleSource;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.TermId;
import com.ssafy.thorp.learning.presentation.request.CreateArticleRequest;
import com.ssafy.thorp.learning.presentation.request.PublishRequest;
import com.ssafy.thorp.learning.presentation.request.SaveDraftRequest;
import com.ssafy.thorp.learning.presentation.response.AdminAccessResponse;
import com.ssafy.thorp.learning.presentation.response.AdminArticleListResponse;
import com.ssafy.thorp.learning.presentation.response.AdminArticleResponse;
import com.ssafy.thorp.learning.presentation.response.PublishResponse;
import com.ssafy.thorp.learning.presentation.response.UnpublishResponse;
import com.ssafy.thorp.learning.presentation.support.LearningAdminAccess;
import jakarta.validation.Valid;
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
 * <p>요청 본문 검증(400)은 메서드 본문보다 먼저 돈다. 그래서 권한 없는 계정이 틀린 본문을 보내면 404가 아니라 400을 받는다. 경로가 있다는 사실이 그만큼
 * 드러나지만, 허용되지 않는 메서드의 405도 같은 사실을 알리므로 여기서 더 막지 않는다.
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

    /** 글 만들기. 새 글은 본문이 빈 초안이다. */
    @PostMapping("/articles")
    public ResponseEntity<ApiResponse<AdminArticleResponse>> createArticle(
            @Valid @RequestBody CreateArticleRequest request) {
        adminAccess.requireAdminUserId();
        AdminArticleResponse created =
                AdminArticleResponse.from(
                        adminService.create(
                                new CreateArticleCommand(
                                        new ArticleId(request.articleId()),
                                        request.title(),
                                        request.readingOrder())));
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
        return ResponseEntity.ok(
                ApiResponse.ok(
                        AdminArticleResponse.from(
                                adminService.saveDraft(articleId, toCommand(request)))));
    }

    /** 게시. 게시 전 검사에 걸리면 422이고 아무것도 바뀌지 않는다. */
    @PostMapping("/articles/{articleId}/publish")
    public ResponseEntity<ApiResponse<PublishResponse>> publish(
            @PathVariable String articleId, @Valid @RequestBody PublishRequest request) {
        long actorUserId = adminAccess.requireAdminUserId();
        return ResponseEntity.ok(
                ApiResponse.ok(
                        PublishResponse.from(
                                adminService.publish(
                                        articleId,
                                        new PublishCommand(
                                                request.expectedDraftVersion(), actorUserId)))));
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

    private static SaveDraftCommand toCommand(SaveDraftRequest request) {
        return new SaveDraftCommand(
                request.expectedDraftVersion(),
                request.title(),
                request.question(),
                request.readingOrder(),
                request.blocks().stream().map(LearningAdminController::toBlock).toList(),
                request.example() == null
                        ? null
                        : new ExampleAllocation(
                                request.example().sectors().stream()
                                        .map(
                                                sector ->
                                                        new ExampleAllocation.SectorWeight(
                                                                sector.code(),
                                                                sector.weightTenths()))
                                        .toList()),
                request.sources().stream()
                        .map(source -> new ArticleSource(source.label(), source.url()))
                        .toList(),
                request.terms().stream()
                        .map(
                                term ->
                                        new LearningTerm(
                                                new TermId(term.termId()),
                                                term.label(),
                                                term.sentence()))
                        .toList());
    }

    private static ArticleBlock toBlock(SaveDraftRequest.Block block) {
        return switch (block) {
            case SaveDraftRequest.Heading heading -> new ArticleBlock.HeadingBlock(heading.text());
            case SaveDraftRequest.Paragraph paragraph ->
                    new ArticleBlock.ParagraphBlock(paragraph.text());
            case SaveDraftRequest.TermBlock term ->
                    new ArticleBlock.TermBlock(new TermId(term.termId()));
            case SaveDraftRequest.Hypothetical hypothetical ->
                    new ArticleBlock.HypotheticalBlock(hypothetical.text());
        };
    }
}
