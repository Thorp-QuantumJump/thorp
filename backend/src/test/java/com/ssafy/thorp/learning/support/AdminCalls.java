package com.ssafy.thorp.learning.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.support.Requesters;
import java.nio.charset.StandardCharsets;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 배우기 통합 테스트의 호출 도우미. 상태 코드와 응답 봉투({@code success})를 함께 확인한다. 테스트마다 따로 두면 한쪽만 봉투 확인을 빠뜨린다.
 *
 * <p>관리 요청은 관리 권한 사용자와 CSRF 토큰을 붙인다.
 */
public final class AdminCalls {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final long adminUserId;

    public AdminCalls(MockMvc mockMvc, ObjectMapper objectMapper, long adminUserId) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.adminUserId = adminUserId;
    }

    /** 요청 그대로 보내고 상태 코드를 확인한 뒤 본문 전체를 돌려준다. */
    public JsonNode call(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        String body =
                mockMvc.perform(request)
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    /** 상태를 기대하지 않고 응답 본문만 읽는다. 동시 요청처럼 어느 쪽이 성공할지 정해지지 않을 때 쓴다. */
    public JsonNode callAny(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(request)
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));
    }

    /** 관리 요청. 본문 전체를 돌려준다. */
    public JsonNode admin(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        return call(request.with(Requesters.as(adminUserId)).with(csrf()), expectedStatus);
    }

    /** 성공한 관리 요청의 {@code data}. */
    public JsonNode adminData(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        JsonNode body = admin(request, expectedStatus);
        assertThat(body.get("success").asBoolean()).isTrue();
        return body.get("data");
    }

    /** 실패한 관리 요청의 {@code error}. */
    public JsonNode adminError(MockHttpServletRequestBuilder request, int expectedStatus)
            throws Exception {
        JsonNode body = admin(request, expectedStatus);
        assertThat(body.get("success").asBoolean()).isFalse();
        return body.get("error");
    }
}
