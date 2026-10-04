package com.ssafy.thorp.news.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.news.application.command.NewsDigestQuery;
import com.ssafy.thorp.news.application.result.NewsDigestListResult;
import com.ssafy.thorp.news.application.service.NewsDigestQueryService;
import com.ssafy.thorp.news.infrastructure.config.NewsProperties;
import com.ssafy.thorp.news.presentation.controller.NewsDigestController;
import com.ssafy.thorp.news.presentation.controller.NewsDigestExceptionHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 뉴스 조회 API 계약 시험의 공통 준비물.
 *
 * <p><b>Spring 컨텍스트를 띄우지 않는다.</b> 확인하려는 것은 응답 JSON의 모양이고, 그것은 컨트롤러와 응답 record만으로 결정된다. 전체 컨텍스트를 띄우면
 * PostgreSQL 컨테이너가 필요해져 Docker가 없는 환경에서 계약 시험이 통째로 skip되고, 이 저장소에서 이미 빠듯한 DB 커넥션을 더 쓴다.
 *
 * <p>대신 조회 서비스를 대역으로 바꿔 끼운다. 서비스가 무엇을 돌려주든 컨트롤러가 계약대로 옮기는지만 본다. 서비스의 판단은 별도 시험이 본다.
 *
 * <p>JSON 변환기는 {@code Jackson2ObjectMapperBuilder}로 만든다. 직접 {@code new ObjectMapper()}를 쓰면 시각이
 * epoch 숫자로 나가 실제 응답과 달라지고, 그 차이를 시험이 잡지 못한다.
 */
final class NewsContractTestSupport {

    /** 계약 예시 파일이 있는 곳. 저장소 루트 기준이다. */
    private static final Path EXAMPLES =
            Path.of("../specs/012-market-news-digest/contracts/examples");

    /** 과거 조회 구간 상한. plan이 고정한 값이며 서버 설정의 기본값과 같다. */
    static final int MAX_RANGE_DAYS = 93;

    /**
     * 실제 응답을 만드는 것과 같은 설정의 ObjectMapper.
     *
     * <p>{@code WRITE_DATES_AS_TIMESTAMPS}를 끄는 것은 Spring Boot의 자동 설정이 하는 일이고 {@code
     * Jackson2ObjectMapperBuilder} 기본값이 아니다. 여기서 켜 두면 시각이 epoch 숫자로 나가 계약과 달라진다. 자동 설정이 적용된 진짜 응답은
     * {@code NewsHealthIsolationIT}가 확인한다.
     */
    static final ObjectMapper MAPPER =
            Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    private NewsContractTestSupport() {}

    /**
     * 조회 서비스를 준 함수로 대신하는 MockMvc.
     *
     * <p>{@code properties}는 컨트롤러가 구간 상한을 읽는 데만 쓴다.
     */
    static MockMvc mockMvc(Function<NewsDigestQuery, NewsDigestListResult> stub) {
        NewsDigestQueryService service = new StubQueryService(stub, MAX_RANGE_DAYS);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(MAPPER);
        return MockMvcBuilders.standaloneSetup(new NewsDigestController(service, null, null))
                .setControllerAdvice(new NewsDigestExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    /** 계약 예시 파일을 읽는다. */
    static JsonNode example(String fileName) throws IOException {
        return MAPPER.readTree(
                Files.readString(EXAMPLES.resolve(fileName), StandardCharsets.UTF_8));
    }

    /** 기본 설정. 구간 상한 93일은 plan이 고정한 값이다. */
    static NewsProperties properties() {
        return new NewsProperties(
                com.ssafy.thorp.news.domain.vo.NewsExposureMode.INTERNAL,
                Duration.ofMinutes(30),
                Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                LocalDate.of(2026, 11, 7),
                "Asia/Seoul",
                93,
                20,
                3,
                Duration.ofHours(48),
                new NewsProperties.Http(
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        10_485_760L,
                        Set.of("application/xml"),
                        Set.of("application/json"),
                        List.of("api.gdeltproject.org")));
    }

    /**
     * 조회 서비스 대역.
     *
     * <p>생성자 인자를 전부 null로 두는 것은 이 대역이 {@code query} 말고는 아무것도 하지 않기 때문이다. 다른 메서드를 부르면 즉시 터져서 대역이 조용히
     * 넘어가는 일이 없다.
     */
    private static final class StubQueryService extends NewsDigestQueryService {

        private final Function<NewsDigestQuery, NewsDigestListResult> stub;
        private final int maxRangeDays;

        private StubQueryService(
                Function<NewsDigestQuery, NewsDigestListResult> stub, int maxRangeDays) {
            super(null, null, null, null, null, null, null, null, null, null, null);
            this.stub = stub;
            this.maxRangeDays = maxRangeDays;
        }

        @Override
        public NewsDigestListResult query(
                List<String> sectorIds, Instant from, Instant to, Integer limit) {
            return query(NewsDigestQuery.of(sectorIds, from, to, limit, maxRangeDays));
        }

        @Override
        public NewsDigestListResult query(NewsDigestQuery query) {
            return stub.apply(query);
        }
    }
}
