package com.ssafy.thorp.account.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.account.domain.model.EmailVerification;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 이메일 가입 인증번호의 대조·재발송 상한(2026-10-01 남은 위험 보완)을 실제 PostgreSQL 위에서 확인한다.
 *
 * <p>메일 발송기 목이 필요해 소셜 통합 테스트의 기반을 함께 쓴다. 같은 컨텍스트를 쓰므로 DB 연결이 더 늘지 않는다.
 */
class EmailVerificationLimitTest extends SocialLoginIntegrationTestSupport {

    private static final String EMAIL = PREFIX + "email-limit@example.com";

    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clearVerifications() {
        jdbcTemplate.update("delete from email_verifications where email like ?", PREFIX + "%");
    }

    @Test
    @DisplayName("같은 주소로 틀린 번호를 동시에 보내도 5번까지만 대조하고, 그 뒤엔 맞는 번호도 다시 받으라고 거절한다")
    void parallelGuessesAreCappedUnderRowLock() throws Exception {
        String right = requestCode();
        String wrong = right.equals("000000") ? "111111" : "000000";

        Cookie[] jar = cookies.values().toArray(Cookie[]::new);
        String csrf = cookies.get("XSRF-TOKEN").getValue();
        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(
                    pool.submit(
                            () -> {
                                start.await();
                                return code(
                                        mockMvc.perform(
                                                        confirm(wrong)
                                                                .cookie(jar)
                                                                .header("X-XSRF-TOKEN", csrf))
                                                .andReturn());
                            }));
        }
        start.countDown();
        Map<String, Integer> counts = new TreeMap<>();
        for (Future<String> result : results) {
            counts.merge(result.get(30, TimeUnit.SECONDS), 1, Integer::sum);
        }
        pool.shutdown();

        assertThat(counts.get("EMAIL_VERIFICATION_INVALID"))
                .as(counts.toString())
                .isEqualTo(EmailVerification.MAX_ATTEMPTS);
        assertThat(counts.get("EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED"))
                .as(counts.toString())
                .isEqualTo(attempts - EmailVerification.MAX_ATTEMPTS);
        assertThat(code(sendWithCsrf(confirm(right))))
                .isEqualTo("EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select verified_at is null from email_verifications where email = ?",
                                Boolean.class,
                                EMAIL))
                .isTrue();
    }

    @Test
    @DisplayName("다시 받기 간격 안에 또 요청하면 429이고 메일을 보내지 않는다")
    void resendTooSoonIsRejected() throws Exception {
        requestCode();

        MvcResult again = sendWithCsrf(request());

        assertThat(again.getResponse().getStatus()).isEqualTo(429);
        assertThat(code(again)).isEqualTo("EMAIL_VERIFICATION_RESEND_TOO_SOON");
        verify(mailSender, org.mockito.Mockito.times(1))
                .sendVerificationCode(eq(EMAIL), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("같은 주소로 재발송을 동시에 보내도 메일은 한 통만 나가고 나머지는 429다")
    void parallelResendsSendOnce() throws Exception {
        sendWithCsrf(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/auth/csrf"));
        Cookie[] jar = cookies.values().toArray(Cookie[]::new);
        String csrf = cookies.get("XSRF-TOKEN").getValue();
        int requests = 6;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            results.add(
                    pool.submit(
                            () -> {
                                start.await();
                                return mockMvc.perform(
                                                request().cookie(jar).header("X-XSRF-TOKEN", csrf))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            }));
        }
        start.countDown();
        Map<Integer, Integer> statuses = new TreeMap<>();
        for (Future<Integer> result : results) {
            statuses.merge(result.get(30, TimeUnit.SECONDS), 1, Integer::sum);
        }
        pool.shutdown();

        assertThat(statuses)
                .as(statuses.toString())
                .containsEntry(204, 1)
                .containsEntry(429, requests - 1);
        verify(mailSender, org.mockito.Mockito.times(1))
                .sendVerificationCode(eq(EMAIL), org.mockito.ArgumentMatchers.anyString());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "select count(*) from email_verifications where email = ?",
                                Integer.class,
                                EMAIL))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("맞는 번호는 상한 안에서 그대로 확인된다")
    void rightCodeStillVerifies() throws Exception {
        String right = requestCode();

        assertThat(sendWithCsrf(confirm(right)).getResponse().getStatus()).isEqualTo(204);
    }

    private String requestCode() throws Exception {
        assertThat(sendWithCsrf(request()).getResponse().getStatus()).isEqualTo(204);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendVerificationCode(eq(EMAIL), sent.capture());
        return sent.getValue();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            request() {
        return post("/accounts/email-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            confirm(String code) {
        return post("/accounts/email-verifications/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"verificationCode\":\"" + code + "\"}");
    }

    private String code(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.isEmpty()
                ? String.valueOf(result.getResponse().getStatus())
                : objectMapper.readTree(body).path("error").path("code").asText();
    }
}
