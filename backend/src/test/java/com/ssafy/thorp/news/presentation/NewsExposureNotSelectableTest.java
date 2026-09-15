package com.ssafy.thorp.news.presentation;

import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.MAPPER;
import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.example;
import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.mockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.application.service.NewsFreshnessPolicy;
import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import com.ssafy.thorp.news.presentation.controller.NewsDigestController;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 노출 모드를 바깥에서 고를 수 없음을 고정한다(spec FR-016, plan "조회와 상태", T045).
 *
 * <p>노출 모드는 배포 단위가 정한다. 질의 문자열이나 헤더로 고를 수 있게 되는 순간, 회신하지 않은 매체의 기사를 누구나 꺼낼 수 있다. 그런 파라미터는 편의를 위해 슬쩍
 * 들어오기 쉽고(내부 확인용이라는 이름으로), 한 번 들어오면 지우기 어렵다.
 *
 * <p>그래서 <b>엔드포인트가 받는 파라미터 목록 자체를 고정한다.</b> 동작을 확인하는 시험만 두면, 누가 모드 파라미터를 더해도 그 시험은 그대로 통과한다 — 대역이
 * 모드를 무시하기 때문이다. 목록을 고정해야 새 파라미터가 리뷰에 걸린다.
 */
class NewsExposureNotSelectableTest {

    /** 이 엔드포인트가 받기로 한 질의 파라미터. 계약(contracts/openapi.yaml)에 적힌 셋이 전부다. */
    private static final List<String> ALLOWED_PARAMETERS = List.of("sectorId", "from", "to");

    private static NewsDigestListResult preparing() {
        return new NewsDigestListResult(
                NewsDigestQuery.Context.CURRENT,
                NewsDigestState.PREPARING,
                null,
                null,
                null,
                null,
                false,
                NewsFreshnessPolicy.PREPARING_MESSAGE,
                List.of());
    }

    @Test
    @DisplayName("엔드포인트가 받는 질의 파라미터는 셋뿐이다")
    void 파라미터_목록_고정() {
        Method endpoint =
                Arrays.stream(NewsDigestController.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("getDigests"))
                        .findFirst()
                        .orElseThrow();

        assertThat(Arrays.stream(endpoint.getParameters()).map(Parameter::getName))
                .as("노출 모드를 고를 수 있는 파라미터가 들어오면 여기서 걸린다")
                .allMatch(
                        name ->
                                ALLOWED_PARAMETERS.stream()
                                                .anyMatch(allowed -> allowed.equalsIgnoreCase(name))
                                        || name.startsWith("arg"));
        assertThat(endpoint.getParameterCount()).isEqualTo(ALLOWED_PARAMETERS.size());
    }

    @Test
    @DisplayName("모드를 고르려는 질의 문자열은 무시된다")
    void 모드_파라미터는_무시() throws Exception {
        String body =
                mockMvc(query -> preparing())
                        .perform(
                                get("/market-news/digests")
                                        .param("exposureMode", "INTERNAL")
                                        .param("mode", "internal")
                                        .param("includeUnpermitted", "true"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(MAPPER.readTree(body))
                .as("모르는 파라미터는 조용히 버린다. 거부하면 추적 파라미터가 붙은 링크가 깨진다")
                .isEqualTo(example("public-preparing.json"));
    }

    @Test
    @DisplayName("응답이 어느 모드로 답했는지 밝히지 않는다")
    void 응답에_모드가_없다() throws Exception {
        String body =
                mockMvc(query -> preparing())
                        .perform(get("/market-news/digests"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body.toLowerCase())
                .as("어느 환경이 무엇을 내보내는지는 서버가 알 일이지 클라이언트가 알 일이 아니다")
                .doesNotContain("exposuremode")
                .doesNotContain("\"internal\"")
                .doesNotContain("\"public\"");
    }
}
