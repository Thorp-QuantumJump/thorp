package com.ssafy.thorp.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.news.infrastructure.config.NewsHealthIndicator;
import com.ssafy.thorp.support.IntegrationTestSupport;
import com.ssafy.thorp.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 뉴스가 비어 있어도 서비스가 정상으로 보이는지(spec FR-024·SC-004, T036).
 *
 * <p>여기서 확인하는 것은 셋이다.
 *
 * <ol>
 *   <li>수집 이력이 하나도 없는데도 {@code /actuator/health}가 UP이다 — 뉴스는 시장 흐름의 보조 맥락이고, 여기서 DOWN이 나오면 배포 판정과
 *       경보가 외부 뉴스원의 사정에 끌려간다
 *   <li>뉴스 조회가 오류가 아니라 상태를 담은 200이다
 *   <li>실제 자동 설정이 적용된 ObjectMapper가 시각을 ISO-8601 문자열로 내보낸다
 * </ol>
 *
 * <p>세 번째가 이 시험에만 있는 이유는 계약 시험이 Spring 컨텍스트 없이 도는 대신 ObjectMapper 설정을 손으로 맞춰 두기 때문이다. 그 손맞춤이 실제 자동
 * 설정과 어긋나면 계약 시험은 통과하는데 진짜 응답만 달라진다. 그 틈을 여기서 막는다.
 *
 * <p><b>Spring 컨텍스트를 새로 만들지 않는다.</b> {@link IntegrationTestSupport}의 설정을 그대로 물려받아 캐시된 컨텍스트를 재사용한다.
 *
 * <p>Docker가 없으면 통째로 skip된다({@link PostgresTestSupport}). 결과의 {@code skipped}를 확인한다.
 */
class NewsHealthIsolationIT extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;
    @Autowired private NewsHealthIndicator newsHealth;

    @Test
    @DisplayName("뉴스 수집 이력이 하나도 없어도 뉴스 지표는 UP이다")
    void 전체_상태에_영향_없음() {
        Health health = newsHealth.health();

        assertThat(health.getStatus())
                .as("여기서 DOWN이 나오면 Actuator 전체 집계가 내려가고 배포 판정이 외부 뉴스원에 끌려간다")
                .isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("lastSuccessAt", "NEVER")
                .containsEntry("stale", false);
    }

    @Test
    @DisplayName("뉴스 신선도는 전용 이름으로 등록돼 따로 볼 수 있다")
    void 전용_지표_이름() {
        assertThat(context.containsBean("newsFreshness"))
                .as("bean 이름이 지표 이름이다. 바뀌면 /actuator/health/newsFreshness가 사라진다")
                .isTrue();
        assertThat(context.getBean("newsFreshness")).isInstanceOf(NewsHealthIndicator.class);
    }

    @Test
    @DisplayName("자료가 없어도 조회는 오류가 아니라 상태를 담은 200이다")
    void 빈_자료도_정상_화면() throws Exception {
        mockMvc.perform(get("/market-news/digests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.digests").isEmpty())
                .andExpect(jsonPath("$.result.state").exists());
    }

    @Test
    @DisplayName("잘못된 기간 요청은 400이고 받은 값을 되비추지 않는다")
    void 잘못된_요청() throws Exception {
        mockMvc.perform(get("/market-news/digests").param("from", "2026-07-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @DisplayName("실제 응답의 시각은 ISO-8601 문자열이다")
    void 시각_표기() throws Exception {
        mockMvc.perform(
                        get("/market-news/digests")
                                .param("from", "2026-07-01T00:00:00Z")
                                .param("to", "2026-07-31T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.from").value("2026-07-01T00:00:00Z"))
                .andExpect(jsonPath("$.result.to").value("2026-07-31T00:00:00Z"));
    }
}
