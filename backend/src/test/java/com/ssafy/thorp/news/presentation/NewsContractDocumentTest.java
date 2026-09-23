package com.ssafy.thorp.news.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 계약 문서와 응답 예시가 서로 어긋나지 않는지 본다(T051).
 *
 * <p><b>이 시험이 없으면 계약 문서는 아무도 확인하지 않는 글이 된다.</b> 다른 계약 시험들은 <i>응답과 예시 파일</i>이 같은지만 본다. 그래서 {@code
 * openapi.yaml}의 {@code required}를 고치거나 예시에서 키를 하나 빼도 빌드는 조용히 지나가고, 그 계약을 읽고 화면을 만드는 쪽만 나중에 어긋난 것을
 * 발견한다.
 *
 * <p><b>계약 파일만 고치면 이 시험이 다시 돌지 않는다.</b> Gradle의 test 입력에 저장소 바깥의 {@code specs/}가 들어 있지 않아 캐시가 그대로
 * 살아 있다. 계약을 고친 뒤에는 {@code ./gradlew cleanTest test}로 확인한다. 예시 파일을 읽는 다른 계약 시험들도 마찬가지다.
 *
 * <p>스키마 전체를 검사하지 않는다. 형식·패턴까지 보려면 검증기를 들여와야 하는데, 실제로 어긋나는 것은 거의 언제나 <b>키의 존재</b>다 — 필드를 더하거나 뺄 때
 * 한쪽만 고치기 때문이다.
 */
class NewsContractDocumentTest {

    private static final Path CONTRACTS = Path.of("../specs/012-market-news-digest/contracts");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 예시 파일과 그 응답이 따르는 스키마 이름. */
    private static final Map<String, String> DIGEST_EXAMPLES =
            Map.of(
                    "current-ready.json", "NewsDigestResult",
                    "current-stale.json", "NewsDigestResult",
                    "public-preparing.json", "NewsDigestResult",
                    "historical-empty.json", "NewsDigestResult");

    @Test
    @DisplayName("묶음 조회 예시가 계약이 요구하는 키를 모두 갖는다")
    void 묶음_예시가_계약을_만족한다() throws Exception {
        Map<String, Object> schemas = schemas();

        for (Map.Entry<String, String> entry : DIGEST_EXAMPLES.entrySet()) {
            JsonNode result = example(entry.getKey()).get("result");

            assertThat(keysOf(result))
                    .as(entry.getKey() + "의 result")
                    .containsAll(requiredOf(schemas, entry.getValue()));

            for (JsonNode digest : result.get("digests")) {
                assertThat(keysOf(digest))
                        .as(entry.getKey() + "의 묶음")
                        .containsAll(requiredOf(schemas, "NewsDigest"));
                for (JsonNode article : digest.get("articles")) {
                    assertThat(keysOf(article))
                            .as(entry.getKey() + "의 기사")
                            .containsAll(requiredOf(schemas, "NewsArticle"));
                }
            }
        }
    }

    @Test
    @DisplayName("내보내기 예시가 계약이 요구하는 키를 모두 갖는다")
    void 내보내기_예시가_계약을_만족한다() throws Exception {
        Map<String, Object> schemas = schemas();
        JsonNode result = example("export-allowed.json").get("result");

        assertThat(keysOf(result)).contains("items", "nextCursor");
        for (JsonNode item : result.get("items")) {
            assertThat(keysOf(item)).containsAll(requiredOf(schemas, "NewsArticleExportItem"));
        }
    }

    @Test
    @DisplayName("내보내기 항목은 허용되지 않은 필드를 필수로 두지 않는다")
    void 내보내기_필수는_둘뿐이다() throws Exception {
        assertThat(requiredOf(schemas(), "NewsArticleExportItem"))
                .as("제목·매체명을 필수로 두면 회신이 그 필드를 허용하지 않은 매체의 항목이 계약을 어기게 된다")
                .containsExactlyInAnyOrder("articleId", "publicationTimeStatus");
    }

    @Test
    @DisplayName("섹터 식별자에 형식 규칙을 적어 두지 않는다")
    void 섹터_형식을_고정하지_않는다() throws Exception {
        String raw = Files.readString(CONTRACTS.resolve("openapi.yaml"), StandardCharsets.UTF_8);

        assertThat(raw)
                .as("형식은 spec 001이 정한다. 여기 적어 두면 001이 규칙을 바꾼 날 001은 발급할 수 있는데 뉴스는 거부하는 값이 생긴다")
                .doesNotContain("'^[a-z0-9-]{1,64}$'");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemas() throws Exception {
        try (InputStream in = Files.newInputStream(CONTRACTS.resolve("openapi.yaml"))) {
            Map<String, Object> document = new Yaml().load(in);
            Map<String, Object> components = (Map<String, Object>) document.get("components");
            return (Map<String, Object>) components.get("schemas");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(Map<String, Object> schemas, String name) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        assertThat(schema).as("계약에 " + name + " 스키마가 있어야 한다").isNotNull();
        return (List<String>) schema.get("required");
    }

    private static JsonNode example(String fileName) throws Exception {
        return MAPPER.readTree(
                Files.readString(
                        CONTRACTS.resolve("examples").resolve(fileName), StandardCharsets.UTF_8));
    }

    private static List<String> keysOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
