package com.ssafy.thorp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.Requesters;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 배우기 관리 경로의 인증·권한·CSRF(learning-admin-api §0, quickstart 3-1).
 *
 * <p>관리 계정은 {@code PostgresTestSupport}가 모든 DB 테스트에 주는 {@code thorp.learning.admin-user-ids=7}이다.
 * 8은 허용 목록에 없는 로그인 계정이다.
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@link IntegrationTestSupport}의 설정을 그대로 물려받아 캐시된 컨텍스트를 재사용한다.
 */
class LearningAdminAccessIT extends IntegrationTestSupport {

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
     * 관리 열한 엔드포인트(글 여덟, 단계 셋). 본문은 검증을 통과하는 값이다. 틀린 본문이면 권한 판정보다 요청 검증(400)이 먼저 돈다. 요청은 권한 판정에서
     * 끝나므로 DB는 바뀌지 않는다.
     */
    private record Endpoint(boolean changesState, MockHttpServletRequestBuilder request) {}

    private static List<Endpoint> adminEndpoints() {
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
                                        "{\"articleId\":\"new-article\",\"title\":\"t\",\"stageKey\":\"returns-and-risk\",\"stagePosition\":5}")),
                new Endpoint(false, get(ARTICLE)),
                new Endpoint(
                        true,
                        put(ARTICLE).contentType(MediaType.APPLICATION_JSON).content(saveBody)),
                new Endpoint(
                        true,
                        post(ARTICLE + "/publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedDraftVersion\":0}")),
                new Endpoint(true, post(ARTICLE + "/unpublish")),
                new Endpoint(false, get(ARTICLE + "/preview")),
                new Endpoint(false, get("/admin/learning/stages")),
                new Endpoint(false, get("/admin/learning/stages/etf-market-basics/readiness")),
                new Endpoint(
                        true,
                        post("/admin/learning/stages/etf-market-basics/release")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"articles\":[]}")));
    }

    @Test
    @DisplayName("비로그인은 401 AUTHENTICATION_REQUIRED다")
    void anonymousIsUnauthenticated() throws Exception {
        for (String url :
                List.of(
                        "/admin/learning/access",
                        "/admin/learning/articles",
                        "/admin/learning/stages",
                        "/admin/learning/stages/etf-market-basics/readiness")) {
            JsonNode body = call(get(url), 401);

            assertThat(body.at("/error/code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        }
        // 상태를 바꾸는 단계 공개도 CSRF 토큰을 실은 비로그인 요청이면 401이다.
        JsonNode release =
                call(
                        post("/admin/learning/stages/etf-market-basics/release")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"articles\":[]}")
                                .with(csrf()),
                        401);
        assertThat(release.at("/error/code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("허용 목록에 없는 로그인 계정은 열한 엔드포인트 모두 404 RESOURCE_NOT_FOUND이고 admin:false 200이 없다")
    void nonAdminGetsNotFoundEverywhere() throws Exception {
        for (Endpoint endpoint : adminEndpoints()) {
            JsonNode body =
                    call(endpoint.request().with(Requesters.as(NOT_ADMIN)).with(csrf()), 404);

            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.at("/error/code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(body.get("data").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("권한 없는 계정이 틀린 본문을 보내도 400과 필드 목록이 아니라 404다 (research R6)")
    void nonAdminWithInvalidBodyGetsNotFound() throws Exception {
        for (String url : List.of(ARTICLE, "/admin/learning/articles", ARTICLE + "/publish")) {
            MockHttpServletRequestBuilder request = url.equals(ARTICLE) ? put(url) : post(url);
            JsonNode body =
                    call(
                            request.contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"blocks\":[{\"type\":\"video\"}]}")
                                    .with(Requesters.as(NOT_ADMIN))
                                    .with(csrf()),
                            404);

            assertThat(body.at("/error/code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(body.at("/error/details").isMissingNode()).isTrue();
        }
    }

    @Test
    @DisplayName("관리 계정이라도 상태 변경 요청에 CSRF 토큰이 없으면 403 CSRF_TOKEN_INVALID다")
    void stateChangeWithoutCsrfIsForbidden() throws Exception {
        for (Endpoint endpoint : adminEndpoints()) {
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
