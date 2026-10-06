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
        // 번호의 주인임이 확인되지 않았으므로 그 계정의 이메일(가린 것 포함)과 연결 제공자를 알려 주지 않는다.
        assertThat(pending.get("linkTargetMaskedEmail").isNull()).isTrue();
        assertThat(pending.get("linkMethods").toString())
                .isEqualTo("[\"PASSWORD\",\"EMAIL_CODE\"]");

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
    @DisplayName("같은 세션으로 틀린 번호를 동시에 보내도 번호 하나는 5번까지만 대조하고, 그 뒤엔 맞는 번호도 받지 않는다")
    void parallelCodeGuessesAreCappedUnderRowLock() throws Exception {
        User owner = saveUser(PREFIX + "race-owner@example.com", "01099100009", "password123");
        loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "race@example.com", true, "카카오닉"));
        sendWithCsrf(json(post("/auth/social/signup"), signupBody("새회원", "01099100009")));
        sendWithCsrf(post("/auth/social/pending/email-verifications"));
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(mailSender)
                .sendVerificationCode(eq(PREFIX + "race-owner@example.com"), sent.capture());
        String right = sent.getValue();
        String wrong = other(right);

        // 쿠키 상자를 건드리지 않고 같은 쿠키·CSRF 헤더로 동시에 보낸다. 세션 속성으로 세면 모두 '0번 틀림'을 읽어 상한을 넘는다.
        jakarta.servlet.http.Cookie[] jar =
                cookies.values().toArray(jakarta.servlet.http.Cookie[]::new);
        String csrf = cookies.get("XSRF-TOKEN").getValue();
        int attempts = 20;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(attempts);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(
                    pool.submit(
                            () -> {
                                start.await();
                                MvcResult result =
                                        mockMvc.perform(
                                                        json(
                                                                        post("/auth/social/link"),
                                                                        "{\"method\":\"EMAIL_CODE\",\"verificationCode\":\""
                                                                                + wrong
                                                                                + "\"}")
                                                                .cookie(jar)
                                                                .header("X-XSRF-TOKEN", csrf))
                                                .andReturn();
                                return code(result);
                            }));
        }
        start.countDown();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (java.util.concurrent.Future<String> result : results) {
            counts.merge(result.get(30, java.util.concurrent.TimeUnit.SECONDS), 1, Integer::sum);
        }
        pool.shutdown();

        assertThat(counts.get("EMAIL_VERIFICATION_INVALID"))
                .as(counts.toString())
                .isEqualTo(
                        com.ssafy.thorp.account.domain.model.SocialVerification.MAX_CODE_ATTEMPTS);
        // 나머지는 모두 대조 없이 거절이다. 상한에 처음 닿은 요청이 번호를 지우고(ATTEMPTS_EXCEEDED), 그 뒤는 번호가 없거나(NOT_FOUND)
        // 같은 계정을 향한 시도가 한 시간 상한을 넘었다(LOCKED).
        assertThat(
                        counts.getOrDefault("EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED", 0)
                                + counts.getOrDefault("EMAIL_VERIFICATION_NOT_FOUND", 0)
                                + counts.getOrDefault("SOCIAL_LINK_TEMPORARILY_LOCKED", 0))
                .as(counts.toString())
                .isEqualTo(
                        attempts
                                - com.ssafy.thorp.account.domain.model.SocialVerification
                                        .MAX_CODE_ATTEMPTS);

        MvcResult late =
                sendWithCsrf(
                        json(
                                post("/auth/social/link"),
                                "{\"method\":\"EMAIL_CODE\",\"verificationCode\":\""
                                        + right
                                        + "\"}"));
        assertThat(code(late))
                .isIn("EMAIL_VERIFICATION_NOT_FOUND", "SOCIAL_LINK_TEMPORARILY_LOCKED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from social_accounts where user_id = ?",
                                Integer.class,
                                owner.getId()))
                .isZero();
    }

    @Test
    @DisplayName("제공자 로그인을 다시 해 새 대기 상태로 와도 같은 계정을 향한 비밀번호 대입은 누적해 429로 막는다")
    void targetLockSurvivesRestartedSocialLogin() throws Exception {
        User owner = saveUser(PREFIX + "target-owner@example.com", "01099100010", "password123");
        int perPending =
                com.ssafy.thorp.account.domain.model.SocialVerification.MAX_PASSWORD_ATTEMPTS;
        int restarts =
                com.ssafy.thorp.account.domain.model.SocialLinkTarget.MAX_ATTEMPTS / perPending;

        for (int restart = 0; restart < restarts; restart++) {
            // 공격자가 자기 카카오로 처음부터 다시 로그인한다. 대기 상태와 그 확인 기록은 새것이다.
            loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "attacker@example.com", true, "닉네임"));
            sendWithCsrf(json(post("/auth/social/signup"), signupBody("새회원", "01099100010")));
            for (int i = 0; i < perPending; i++) {
                assertThat(
                                code(
                                        sendWithCsrf(
                                                json(
                                                        post("/auth/social/link"),
                                                        "{\"method\":\"PASSWORD\",\"password\":\"wrong-"
                                                                + i
                                                                + "\"}"))))
                        .isEqualTo("INVALID_CREDENTIALS");
            }
        }

        loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "attacker@example.com", true, "닉네임"));
        sendWithCsrf(json(post("/auth/social/signup"), signupBody("새회원", "01099100010")));
        MvcResult locked =
                sendWithCsrf(
                        json(
                                post("/auth/social/link"),
                                "{\"method\":\"PASSWORD\",\"password\":\"password123\"}"));

        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(code(locked)).isEqualTo("SOCIAL_LINK_TEMPORARILY_LOCKED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from social_accounts where user_id = ?",
                                Integer.class,
                                owner.getId()))
                .isZero();
        // 막힌 것은 소셜 연결뿐이다. 주인은 이메일로 그대로 로그인한다.
        cookies.clear();
        MvcResult ownerLogin =
                sendWithCsrf(
                        json(
                                post("/auth/sessions"),
                                "{\"email\":\""
                                        + PREFIX
                                        + "target-owner@example.com\",\"password\":\"password123\"}"));
        assertThat(ownerLogin.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("다시 받기 간격 안에 또 요청하면 429 EMAIL_VERIFICATION_RESEND_TOO_SOON이고 메일을 보내지 않는다")
    void resendTooSoonIsRejected() throws Exception {
        loginWithKakao(kakaoUser(KAKAO_ID, PREFIX + "resend@example.com", false, "카카오닉"));
        assertThat(
                        sendWithCsrf(post("/auth/social/pending/email-verifications"))
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);

        MvcResult again = sendWithCsrf(post("/auth/social/pending/email-verifications"));

        assertThat(again.getResponse().getStatus()).isEqualTo(429);
        assertThat(code(again)).isEqualTo("EMAIL_VERIFICATION_RESEND_TOO_SOON");
        verify(mailSender, org.mockito.Mockito.times(1))
                .sendVerificationCode(
                        eq(PREFIX + "resend@example.com"),
                        org.mockito.ArgumentMatchers.anyString());
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

    @Test
    @DisplayName("소셜 가입은 휴대폰 번호 없이 닉네임만으로 마치고 계정의 번호는 비어 있다")
    void signupWithoutPhoneNumber() throws Exception {
        String email = PREFIX + "nophone@example.com";
        loginWithKakao(kakaoUser(KAKAO_ID, email, true, "카카오닉"));

        data(sendWithCsrf(json(post("/auth/social/signup"), "{\"nickname\":\"새회원\"}")), 201);

        assertThat(
                        jdbcTemplate.queryForObject(
                                "select phone_number is null from users where email = ?",
                                Boolean.class,
                                email))
                .isTrue();
    }

    private static String signupBody(String nickname, String phone) {
        return "{\"nickname\":\"" + nickname + "\",\"phoneNumber\":\"" + phone + "\"}";
    }

    private static String other(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }
}
