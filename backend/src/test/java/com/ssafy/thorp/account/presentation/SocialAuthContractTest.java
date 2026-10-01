package com.ssafy.thorp.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.account.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 소셜 JSON API의 계약(T066, contracts/auth-api.yaml의 소셜 operation).
 *
 * <p>대기 상태는 실제 카카오 콜백으로 만든다. 세션 속성을 테스트가 직접 넣으면 운영 경로(필터 → 처리기 → 세션 저장소)가 검증되지 않는다.
 */
class SocialAuthContractTest extends SocialLoginIntegrationTestSupport {

    private static final long KAKAO_ID = 4100000002L;

    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("소셜 가입: 대기 상태 조회 → 추가 정보 제출 → 세션 교체·로그인, 비밀번호 없는 계정과 연결이 남는다")
    void signupFlow() throws Exception {
        String email = PREFIX + "signup@example.com";
        loginWithKakao(kakaoUser(KAKAO_ID, email, true, "카카오닉"));

        JsonNode pending = data(send(get("/auth/social/pending")), 200);
        assertThat(pending.get("provider").asText()).isEqualTo("kakao");
        assertThat(pending.get("stage").asText()).isEqualTo("SIGNUP");
        assertThat(pending.get("email").asText()).isEqualTo(email);
        assertThat(pending.get("suggestedNickname").asText()).isEqualTo("카카오닉");
        assertThat(pending.get("linkMethods")).isEmpty();
        assertThat(pending.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "provider",
                        "stage",
                        "email",
                        "suggestedNickname",
                        "linkReason",
                        "linkTargetMaskedEmail",
                        "linkMethods",
                        "expiresAt");

        String before = cookies.get("THORP_SESSION").getValue();
        JsonNode user =
                data(
                        sendWithCsrf(
                                json(
                                        post("/auth/social/signup"),
                                        signupBody("새회원", "01099100001"))),
                        201);
        assertThat(user.get("email").asText()).isEqualTo(email);
        assertThat(user.get("nickname").asText()).isEqualTo("새회원");
        assertThat(cookies.get("THORP_SESSION").getValue()).isNotEqualTo(before);

        assertThat(send(get("/auth/me")).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(get("/auth/social/pending")).getResponse().getStatus()).isEqualTo(404);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select password_hash is null from users where email = ?",
                                Boolean.class,
                                email))
                .isTrue();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from social_accounts s join users u on u.id = s.user_id"
                                        + " where u.email = ? and s.provider = 'KAKAO' and s.provider_user_id = ?",
                                Integer.class,
                                email,
                                String.valueOf(KAKAO_ID)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("소셜로만 가입한 계정은 이메일·비밀번호 로그인에서 일반 실패와 같은 401이다")
    void passwordlessAccountCannotUseEmailLogin() throws Exception {
        String email = PREFIX + "nopw@example.com";
        loginWithKakao(kakaoUser(KAKAO_ID, email, true, "카카오닉"));
        sendWithCsrf(json(post("/auth/social/signup"), signupBody("새회원", "01099100002")));
        sendWithCsrf(delete("/auth/sessions/current"));

        MvcResult login =
                sendWithCsrf(
                        json(
                                post("/auth/sessions"),
                                "{\"email\":\"" + email + "\",\"password\":\"anything123\"}"));

        assertThat(login.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(login)).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("상태를 바꾸는 소셜 요청은 CSRF 헤더가 없으면 403이고 아무것도 만들지 않는다")
    void signupWithoutCsrfIsForbidden() throws Exception {
        String email = PREFIX + "csrf@example.com";
        loginWithKakao(kakaoUser(KAKAO_ID, email, true, "카카오닉"));

        MvcResult result =
                send(json(post("/auth/social/signup"), signupBody("새회원", "01099100003")));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(result)).isEqualTo("CSRF_TOKEN_INVALID");
        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    @Test
    @DisplayName("휴대폰 번호가 겹치면 409 SOCIAL_LINK_REQUIRED, 연결 단계로 바뀌고 비밀번호로 확인하면 그 계정에 연결·로그인한다")
    void duplicatePhoneLeadsToPasswordLink() throws Exception {
        User owner = saveUser(PREFIX + "phone-owner@example.com", "01099100004", "password123");
        loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "other@example.com", true, "카카오닉"));

        MvcResult signup =
                sendWithCsrf(json(post("/auth/social/signup"), signupBody("새회원", "01099100004")));
        assertThat(signup.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(signup)).isEqualTo("SOCIAL_LINK_REQUIRED");

        JsonNode pending = data(send(get("/auth/social/pending")), 200);
        assertThat(pending.get("stage").asText()).isEqualTo("LINK");
        assertThat(pending.get("linkReason").asText()).isEqualTo("PHONE");
        assertThat(pending.get("linkTargetMaskedEmail").asText())
                .isEqualTo("s***@example.com")
                .doesNotContain("phone-owner");
        assertThat(pending.get("linkMethods").toString()).contains("PASSWORD", "EMAIL_CODE");

        MvcResult wrong =
                sendWithCsrf(
                        json(
                                post("/auth/social/link"),
                                "{\"method\":\"PASSWORD\",\"password\":\"wrong\"}"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(wrong)).isEqualTo("INVALID_CREDENTIALS");

        JsonNode user =
                data(
                        sendWithCsrf(
                                json(
                                        post("/auth/social/link"),
                                        "{\"method\":\"PASSWORD\",\"password\":\"password123\"}")),
                        200);
        assertThat(user.get("userId").asLong()).isEqualTo(owner.getId());
        assertThat(send(get("/auth/me")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("이메일이 같은 기존 계정은 그 계정 이메일로 받은 인증번호로 연결한다")
    void emailMatchLinksWithTargetEmailCode() throws Exception {
        String email = PREFIX + "code-owner@example.com";
        User owner = saveUser(email, "01099100005", "password123");
        assertThat(loginWithKakao(kakaoUser(KAKAO_ID, email, true, "카카오닉")))
                .isEqualTo("/auth/social/link");

        assertThat(
                        sendWithCsrf(post("/auth/social/pending/email-verifications"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendVerificationCode(eq(email), code.capture());

        JsonNode user =
                data(
                        sendWithCsrf(
                                json(
                                        post("/auth/social/link"),
                                        "{\"method\":\"EMAIL_CODE\",\"verificationCode\":\""
                                                + code.getValue()
                                                + "\"}")),
                        200);
        assertThat(user.get("userId").asLong()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("확인되지 않은 제공자 이메일은 인증번호로 확인한 뒤 가입 단계가 된다")
    void unverifiedEmailIsCheckedByCode() throws Exception {
        String email = PREFIX + "unverified@example.com";
        assertThat(loginWithKakao(kakaoUser(KAKAO_ID, email, false, "카카오닉")))
                .isEqualTo("/auth/social/verify-email");

        // 확인 전에는 가입할 수 없다.
        MvcResult early =
                sendWithCsrf(json(post("/auth/social/signup"), signupBody("새회원", "01099100006")));
        assertThat(early.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(early)).isEqualTo("SOCIAL_PENDING_STAGE_MISMATCH");

        sendWithCsrf(post("/auth/social/pending/email-verifications"));
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendVerificationCode(eq(email), code.capture());

        MvcResult wrong =
                sendWithCsrf(
                        json(
                                post("/auth/social/pending/email-verifications/confirm"),
                                "{\"verificationCode\":\"" + other(code.getValue()) + "\"}"));
        assertThat(code(wrong)).isEqualTo("EMAIL_VERIFICATION_INVALID");

        JsonNode next =
                data(
                        sendWithCsrf(
                                json(
                                        post("/auth/social/pending/email-verifications/confirm"),
                                        "{\"verificationCode\":\"" + code.getValue() + "\"}")),
                        200);
        assertThat(next.get("stage").asText()).isEqualTo("SIGNUP");
        assertThat(
                        sendWithCsrf(
                                        json(
                                                post("/auth/social/signup"),
                                                signupBody("새회원", "01099100006")))
                                .getResponse()
                                .getStatus())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("번호가 겹쳐 온 연결을 거절하면 가입 단계로 돌아가 다른 번호로 가입한다")
    void declinePhoneLinkThenSignupWithAnotherNumber() throws Exception {
        saveUser(PREFIX + "decline-owner@example.com", "01099100007", "password123");
        String email = PREFIX + "decline@example.com";
        loginWithKakao(kakaoUser(KAKAO_ID, email, true, "카카오닉"));
        sendWithCsrf(json(post("/auth/social/signup"), signupBody("새회원", "01099100007")));

        assertThat(sendWithCsrf(post("/auth/social/link/decline")).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(data(send(get("/auth/social/pending")), 200).get("stage").asText())
                .isEqualTo("SIGNUP");

        JsonNode user =
                data(
                        sendWithCsrf(
                                json(
                                        post("/auth/social/signup"),
                                        signupBody("새회원", "01099100008"))),
                        201);
        assertThat(user.get("email").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("대기 상태를 취소하면 204이고 이후 조회는 404 SOCIAL_PENDING_NOT_FOUND다")
    void cancelPending() throws Exception {
        loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "cancel@example.com", true, "카카오닉"));

        assertThat(sendWithCsrf(delete("/auth/social/pending")).getResponse().getStatus())
                .isEqualTo(204);
        MvcResult after = send(get("/auth/social/pending"));
        assertThat(after.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(after)).isEqualTo("SOCIAL_PENDING_NOT_FOUND");
    }

    @Test
    @DisplayName("추가 정보 형식 오류는 400 INVALID_INPUT이고 계정을 만들지 않는다")
    void invalidSignupInput() throws Exception {
        String email = PREFIX + "invalid@example.com";
        loginWithKakao(kakaoUser(KAKAO_ID, email, true, "카카오닉"));

        MvcResult result =
                sendWithCsrf(json(post("/auth/social/signup"), signupBody("가", "010-1234-5678")));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("INVALID_INPUT");
        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    private JsonNode data(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("error").isNull()).isTrue();
        return body.get("data");
    }

    private String code(MvcResult result) throws Exception {
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("error")
                .path("code")
                .asText();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String signupBody(String nickname, String phone) {
        return "{\"nickname\":\"" + nickname + "\",\"phoneNumber\":\"" + phone + "\"}";
    }

    private static String other(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }
}
