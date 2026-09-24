package com.ssafy.thorp.assistant.presentation.controller;

import com.ssafy.thorp.assistant.application.command.AskQuestionCommand;
import com.ssafy.thorp.assistant.application.result.AssistantAnswer;
import com.ssafy.thorp.assistant.application.service.AssistantCapabilities;
import com.ssafy.thorp.assistant.application.service.AssistantQuestionService;
import com.ssafy.thorp.assistant.presentation.request.AssistantQuestionRequest;
import com.ssafy.thorp.assistant.presentation.response.AssistantAnswerResponse;
import com.ssafy.thorp.assistant.presentation.response.AssistantCapabilitiesResponse;
import com.ssafy.thorp.common.web.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면 맥락 도우미(spec 015, 계약 §1). 로그인 불필요 — 결과 화면이 공개다. {@code POST}는 저장소 CSRF 규약을 그대로 받는다.
 *
 * <p>IP 한도의 키는 {@code getRemoteAddr()}다. 운영은 {@code server.forward-headers-strategy=framework}라
 * nginx 뒤에서도 실제 클라이언트 주소가 들어온다.
 */
@RestController
@RequestMapping("/assistant")
public class AssistantController {
    private final AssistantCapabilities capabilities;
    private final AssistantQuestionService service;

    public AssistantController(
            AssistantCapabilities capabilities, AssistantQuestionService service) {
        this.capabilities = capabilities;
        this.service = service;
    }

    /** 플래그와 무관하게 항상 200 이다. 꺼져 있으면 {@code enabled=false}·빈 {@code scopes}. */
    @GetMapping("/capabilities")
    public ResponseEntity<ApiResponse<AssistantCapabilitiesResponse>> capabilities() {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        AssistantCapabilitiesResponse.of(
                                capabilities.enabled(), capabilities.scopes())));
    }

    @PostMapping("/questions")
    public ResponseEntity<ApiResponse<AssistantAnswerResponse>> ask(
            @RequestBody AssistantQuestionRequest request, HttpServletRequest http) {
        AssistantQuestionRequest.Context context = request.context();
        AskQuestionCommand command =
                new AskQuestionCommand(
                        request.question(),
                        context == null ? null : context.kind(),
                        context == null ? null : context.publicToken(),
                        context == null ? Set.of() : Set.copyOf(context.extra().keySet()),
                        http.getRemoteAddr());
        AssistantAnswer answer = service.ask(command);
        return ResponseEntity.ok(ApiResponse.ok(AssistantAnswerResponse.from(answer)));
    }
}
