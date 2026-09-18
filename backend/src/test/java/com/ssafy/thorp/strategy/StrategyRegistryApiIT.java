package com.ssafy.thorp.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.Yaml;

/**
 * 운영 등록표로 조회 경로 전체를 확인한다(T028, quickstart 2).
 *
 * <p>계약 형식은 {@code StrategyRegistryContractTest}가 합성 등록표로 이미 본다. 여기서 보는 것은 <b>실제 자원 파일이 그 경로로 나오는
 * 가</b>다 — 파일이 잘못 적혀 있으면 기동부터 실패하고, 순서나 활성 여부가 어긋나면 화면이 다른 카드를 보인다.
 *
 * <p><b>기대값을 테스트에 적지 않는다.</b> 전략 ID와 버전을 여기에 옮겨 적으면 전략 데이터가 두 곳에 생기고, 자원 파일을 고쳐도 통과하는(또는 이유 없이 깨지는)
 * 상태가 된다. 그래서 자원 파일을 따로 한 번 더 읽어 응답과 대조한다({@code FileParameterRegistryTest}와 같은 방식). 다섯이라는 개수만 spec
 * FR-001이 정한 값이라 그대로 단언한다.
 *
 * <p>브로커 컨테이너는 띄우지 않는다({@code BacktestResultApiIT}와 같은 방식). 등록표 조회에 DB도 필요하지 않지만, 전체 컨텍스트를 띄우는 이상
 * 스키마 검증은 함께 돈다.
 */
class StrategyRegistryApiIT extends IntegrationTestSupport {

    private static final String REGISTRY_RESOURCE = "registry/parameter-registry.yml";

    /** spec FR-001이 정한 배분 전략의 수. 등록표가 이보다 적거나 많으면 화면이 FR-001과 다른 카드를 보인다. */
    private static final int STRATEGY_COUNT = 5;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("로그인하지 않고도 활성 전략 다섯을 등록표 순서대로 받는다")
    void anonymousRequestReturnsActiveStrategiesInOrder() throws Exception {
        // 요청자 헤더를 붙이지 않는다. 전략 진단은 비로그인 이용을 허용한다(계약 §1).
        JsonNode data = data(3);
        List<String> responded = new ArrayList<>();
        data.get("strategies")
                .forEach(strategy -> responded.add(strategy.get("strategyId").asText()));

        assertThat(responded)
                .hasSize(STRATEGY_COUNT)
                .containsExactlyElementsOf(activeStrategyIds());
    }

    @Test
    @DisplayName("섹터 수를 주면 고를 수 없는 후보가 생기되 목록의 길이는 그대로다 (quickstart 3)")
    void unselectableChoicesRemainInTheList() throws Exception {
        // 섹터가 둘이면 그보다 많이 고르게 하는 후보는 고를 수 없다.
        JsonNode constrained = data(2);
        JsonNode unconstrained = data(null);

        assertThat(unselectableChoices(constrained)).isPositive();
        // 섹터 수를 주지 않으면 제약을 적용하지 않는다 — 판정할 근거가 없다.
        assertThat(unselectableChoices(unconstrained)).isZero();

        // 목록에서 빼지 않는다. 사라지면 사용자가 왜 고를 수 없는지 알 방법이 없다.
        assertThat(choiceCounts(constrained)).isEqualTo(choiceCounts(unconstrained));
    }

    @Test
    @DisplayName("응답의 등록표 버전이 자원 파일과 같고 임시값이 아니다")
    void versionMatchesTheResourceAndIsNotProvisional() throws Exception {
        JsonNode data = data(null);
        String version = registryDocument().get("version").toString();

        assertThat(data.get("registryVersion").asText()).isEqualTo(version);
        // PD-02가 확정된 제품 등록표다. PROVISIONAL- 접두가 남아 있으면 화면이 임시값 표시를 계속 달고 있게 된다.
        assertThat(data.get("provisional").asBoolean()).isFalse();
        assertThat(version).doesNotStartWith("PROVISIONAL-");
    }

    /** 조회 응답의 {@code data}. {@code universeSize}가 null이면 붙이지 않는다. */
    private JsonNode data(Integer universeSize) throws Exception {
        MockHttpServletRequestBuilder request = get("/strategies/registry");
        if (universeSize != null) {
            request = request.param("universeSize", String.valueOf(universeSize));
        }
        String body =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data");
    }

    /**
     * 고를 수 없는 후보의 수.
     *
     * <p>어느 전략의 어느 축인지 적지 않는다 — 여기에 축 이름을 적으면 등록표를 고칠 때마다 이 테스트가 따라 바뀐다. 확인하는 것은 "제약을 걸면 고를 수 없는 것이
     * 생긴다"이지 그것이 무엇인지가 아니다.
     */
    private static long unselectableChoices(JsonNode data) {
        long count = 0;
        for (JsonNode choice : allChoices(data)) {
            if (!choice.get("selectable").asBoolean()) {
                count++;
            }
        }
        return count;
    }

    /** 전략·축별 후보 개수. 제약을 걸어도 이 값이 바뀌지 않아야 목록에서 빼지 않은 것이다. */
    private static Map<String, Integer> choiceCounts(JsonNode data) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode strategy : data.get("strategies")) {
            for (JsonNode axis : strategy.get("axes")) {
                JsonNode choices = axis.get("choices");
                if (choices != null) {
                    counts.put(
                            strategy.get("strategyId").asText() + "/" + axis.get("name").asText(),
                            choices.size());
                }
            }
        }
        return counts;
    }

    private static List<JsonNode> allChoices(JsonNode data) {
        List<JsonNode> choices = new ArrayList<>();
        for (JsonNode strategy : data.get("strategies")) {
            for (JsonNode axis : strategy.get("axes")) {
                JsonNode axisChoices = axis.get("choices");
                if (axisChoices != null) {
                    axisChoices.forEach(choices::add);
                }
            }
        }
        return choices;
    }

    private static List<String> activeStrategyIds() throws IOException {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> strategy : strategies()) {
            // 등록표는 active를 적지 않으면 활성이다(data-model §2).
            if (!Boolean.FALSE.equals(strategy.get("active"))) {
                ids.add(String.valueOf(strategy.get("id")));
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> strategies() throws IOException {
        return (List<Map<String, Object>>) registryDocument().get("strategies");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> registryDocument() throws IOException {
        try (InputStream stream = new ClassPathResource(REGISTRY_RESOURCE).getInputStream()) {
            return (Map<String, Object>) new Yaml().load(stream);
        }
    }
}
