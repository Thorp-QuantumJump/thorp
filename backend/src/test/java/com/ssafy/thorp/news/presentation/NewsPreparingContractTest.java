package com.ssafy.thorp.news.presentation;

import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.MAPPER;
import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.example;
import static com.ssafy.thorp.news.presentation.NewsContractTestSupport.mockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.application.service.NewsFreshnessPolicy;
import com.ssafy.thorp.news.domain.vo.NewsDigestState;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 허가를 기다리는 동안의 응답(spec FR-016·FR-024, T042).
 *
 * <p><b>준비 중은 오류가 아니다.</b> 회신한 매체가 하나도 없는 것은 우리가 아직 기다리는 중이라는 뜻이지 화면이 고장 났다는 뜻이 아니다. 여기가 500이나 404가
 * 되면 시장 흐름 화면이 통째로 비고, 화면을 만드는 쪽은 서버 장애와 구별할 수 없다.
 *
 * <p>가장 중요한 것은 <b>준비 중 응답에 매체 정보가 한 조각도 없어야 한다</b>는 것이다. 아직 회신하지 않은 매체의 이름이나 기사 제목이 "준비 중"이라는 말과 함께
 * 나가면, 허가를 기다린다면서 이미 쓰고 있는 셈이 된다.
 *
 * <p>노출 모드를 클라이언트가 고를 수 없다는 것은 {@code NewsExposureNotSelectableTest}가 파라미터 목록째로 고정한다. 그쪽이 더 강한 방식이라
 * 여기서 되풀이하지 않는다.
 */
class NewsPreparingContractTest {

    private static NewsDigestListResult preparing(String sectorId) {
        return new NewsDigestListResult(
                NewsDigestQuery.Context.CURRENT,
                NewsDigestState.PREPARING,
                sectorId,
                null,
                null,
                null,
                false,
                NewsFreshnessPolicy.PREPARING_MESSAGE,
                List.of());
    }

    @Test
    @DisplayName("PREPARING 응답이 계약 예시와 같다")
    void 계약_예시_일치() throws Exception {
        String body =
                mockMvc(query -> preparing(null))
                        .perform(get("/market-news/digests"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(MAPPER.readTree(body))
                .as("문구가 계약과 다르면 화면이 준비한 안내와 서버가 보내는 말이 어긋난다")
                .isEqualTo(example("public-preparing.json"));
    }

    @Test
    @DisplayName("준비 중도 200이고 성공 응답이다")
    void 준비_중은_오류가_아니다() throws Exception {
        mockMvc(query -> preparing(null))
                .perform(get("/market-news/digests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.state").value("PREPARING"))
                .andExpect(jsonPath("$.result.digests").isEmpty());
    }

    @Test
    @DisplayName("준비 중 응답에 매체 이름도 기사도 없다")
    void 매체_정보가_새지_않는다() throws Exception {
        String body =
                mockMvc(query -> preparing(null))
                        .perform(get("/market-news/digests"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        assertThat(MAPPER.readTree(body).at("/result/digests"))
                .as("회신 전 매체의 제목·이름이 '준비 중'과 함께 나가면 기다린다는 말이 거짓이 된다")
                .isEmpty();
        assertThat(body).doesNotContain("publisher").doesNotContain("originalUrl");
    }

    @Test
    @DisplayName("섹터를 지정해도 준비 중 모양은 같고 섹터만 되비친다")
    void 섹터_요청도_같은_모양() throws Exception {
        mockMvc(query -> preparing("SEMICONDUCTOR"))
                .perform(get("/market-news/digests").param("sectorId", "SEMICONDUCTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.state").value("PREPARING"))
                .andExpect(jsonPath("$.result.sectorId").value("SEMICONDUCTOR"))
                .andExpect(
                        jsonPath("$.result.statusMessage")
                                .value(NewsFreshnessPolicy.PREPARING_MESSAGE))
                .andExpect(jsonPath("$.result.digests").isEmpty());
    }
}
