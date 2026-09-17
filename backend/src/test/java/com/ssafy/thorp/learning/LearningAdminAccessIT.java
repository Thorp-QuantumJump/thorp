package com.ssafy.thorp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.support.PostgresTestSupport;
import com.ssafy.thorp.support.Requesters;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 배우기 관리 경로의 인증·권한·CSRF(learning-admin-api §0, quickstart 3-1).
 *
 * <p>관리 계정은 {@code PostgresTestSupport}가 모든 DB 테스트에 주는 {@code thorp.learning.admin-user-ids=7}이다.
 * 8은 허용 목록에 없는 로그인 계정이다.
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@code @SpringBootTest} 속성을 {@code LearningPublicApiIT}와 글자
 * 그대로 같게 둔다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class LearningAdminAccessIT extends PostgresTestSupport {

    private static final long ADMIN = 7;
    private static final long NOT_ADMIN = 8;
    private static final String ARTICLE = "/admin/learning/articles/returns-and-losses";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private JsonNode call(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        String body =
                mockMvc.perform(request)
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    /**
     * 관리 일곱 엔드포인트. 본문은 검증을 통과하는 값이다. 틀린 본문이면 권한 판정보다 요청 검증(400)이 먼저 돈다. 요청은 권한 판정에서 끝나므로 DB는 바뀌지
     * 않는다.
     */
    private record Endpoint(boolean changesState, MockHttpServletRequestBuilder request) {}

    private static List<Endpoint> sevenEndpoints() {
        String saveBody =
                """
                {"expectedDraftVersion":0,"title":"t","question":"q","readingOrder":8,
                 "blocks":[],"example":null,"sources":[],"terms":[]}
                """;
        return List.of(
                new Endpoint(false, get("/admin/learning/access")),
                new Endpoint(false, get("/admin/learning/articles")),
                new Endpoint(
                        true,
                        post("/admin/learning/articles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"articleId\":\"new-article\",\"title\":\"t\",\"readingOrder\":10}")),
                new Endpoint(false, get(ARTICLE)),
                new Endpoint(
                        true,
                        put(ARTICLE).contentType(MediaType.APPLICATION_JSON).content(saveBody)),
                new Endpoint(
                        true,
                        post(ARTICLE + "/publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedDraftVersion\":0}")),
                new Endpoint(true, post(ARTICLE + "/unpublish")));
    }

    @Test
    @DisplayName("비로그인은 401 AUTHENTICATION_REQUIRED다")
    void anonymousIsUnauthenticated() throws Exception {
        for (String url : List.of("/admin/learning/access", "/admin/learning/articles")) {
            JsonNode body = call(get(url), 401);

            assertThat(body.at("/error/code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    @DisplayName("허용 목록에 없는 로그인 계정은 일곱 엔드포인트 모두 404 RESOURCE_NOT_FOUND이고 admin:false 200이 없다")
    void nonAdminGetsNotFoundEverywhere() throws Exception {
        for (Endpoint endpoint : sevenEndpoints()) {
            JsonNode body =
                    call(endpoint.request().with(Requesters.as(NOT_ADMIN)).with(csrf()), 404);

            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.at("/error/code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(body.path("data").path("admin").isMissingNode()).isTrue();
        }
    }

    @Test
    @DisplayName("관리 계정이라도 상태 변경 요청에 CSRF 토큰이 없으면 403 CSRF_TOKEN_INVALID다")
    void stateChangeWithoutCsrfIsForbidden() throws Exception {
        for (Endpoint endpoint : sevenEndpoints()) {
            if (!endpoint.changesState()) {
                continue;
            }
            JsonNode body = call(endpoint.request().with(Requesters.as(ADMIN)), 403);

            assertThat(body.at("/error/code").asText()).isEqualTo("CSRF_TOKEN_INVALID");
        }
    }

    @Test
    @DisplayName("관리 계정의 access는 200 {admin: true}다 (quickstart 3-1)")
    void adminHasAccess() throws Exception {
        JsonNode body = call(get("/admin/learning/access").with(Requesters.as(ADMIN)), 200);

        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("data")).isEqualTo(objectMapper.readTree("{\"admin\":true}"));
    }
}
