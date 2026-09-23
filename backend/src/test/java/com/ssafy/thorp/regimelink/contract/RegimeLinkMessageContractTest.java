package com.ssafy.thorp.regimelink.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.ssafy.thorp.regimelink.application.port.message.BasisKind;
import com.ssafy.thorp.regimelink.application.port.message.FillSide;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkErrorCode;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkEvent;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkEventType;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkFinishedPayload;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkResultPayload;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkScenario;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkStartedPayload;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkStatus;
import com.ssafy.thorp.regimelink.application.port.message.TurnoverBasis;
import com.ssafy.thorp.regimelink.infrastructure.messaging.RegimeLinkMessageJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 계약 픽스처({@code docs/specs/regime-link-messaging/examples})를 record로 읽고 다시 써서 원본과 정확 동치인지 확인한다.
 *
 * <p>워커 테스트({@code worker/tests/regime_link/test_contracts.py})가 <b>같은 파일</b>을 읽는다. 한쪽만 고치면 다른 쪽이
 * 깨지는 것이 이 테스트의 목적이다 — 픽스처가 양쪽 코드의 단일 기준이다.
 *
 * <p>픽스처에는 null 값이 없고 생략 가능한 필드는 키가 빠져 있으므로 정규화 없이 JSON 트리를 그대로 비교한다(키 순서만 무시). gradle test의 작업
 * 디렉터리는 {@code backend/}다.
 */
class RegimeLinkMessageContractTest {

    private static final Path FIXTURES =
            Path.of("..", "docs", "specs", "regime-link-messaging", "examples");

    private static final TypeReference<RegimeLinkEvent<RegimeLinkStartedPayload>> STARTED =
            new TypeReference<>() {};
    private static final TypeReference<RegimeLinkEvent<RegimeLinkFinishedPayload>> FINISHED =
            new TypeReference<>() {};

    private final ObjectMapper mapper = RegimeLinkMessageJson.objectMapper();

    @Test
    void 연속_운용_작업() throws IOException {
        RegimeLinkJob job = roundTrip("job-continuous.json", new TypeReference<>() {});

        assertThat(job.job().linkId()).isEqualTo("RL-20260918-000101");
        assertThat(job.job().attempt()).isEqualTo(1);
        assertThat(job.job().scenario()).isEqualTo(RegimeLinkScenario.CONTINUOUS);
        // baselineDate 행이 하나 더 있어 행 수 = completedTradingDays + 1 (계약 "픽스처가 보장하는 것")
        assertThat(job.inputs().portfolioSeries()).hasSize(job.window().completedTradingDays() + 1);
        assertThat(job.inputs().portfolioSeries().get(0).date())
                .isEqualTo(job.window().baselineDate());
        // 연속 운용은 시작 보유가 있다
        assertThat(job.inputs().openingPositions()).isNotEmpty();
        assertThat(job.inputs().openingPositions().get(0).quantity()).isEqualTo(179L);
        assertThat(job.inputs().trades().get(0).side()).isEqualTo(FillSide.SELL);
        assertThat(job.inputs().cashFlows().get(0).type()).isEqualTo("DISTRIBUTION");
        assertThat(job.inputDigest()).hasSize(64);
    }

    @Test
    void 현금_시작_작업() throws IOException {
        RegimeLinkJob job = roundTrip("job-fresh-cash.json", new TypeReference<>() {});

        assertThat(job.job().scenario()).isEqualTo(RegimeLinkScenario.FRESH_CASH);
        // 기준은 run.initialCash이고 시작 보유는 빈 목록이다(research R3)
        assertThat(job.inputs().openingPositions()).isEmpty();
        assertThat(job.run().initialCash()).isEqualByComparingTo("10000000.00");
        assertThat(job.inputs().trades().get(0).side()).isEqualTo(FillSide.BUY);
    }

    @Test
    void 구간_안_상장폐지_작업() throws IOException {
        RegimeLinkJob job = roundTrip("job-partial-delisted.json", new TypeReference<>() {});

        // 백엔드가 이미 폐지 전날까지로 줄여 보낸다 — 워커가 다시 줄이지 않는다
        assertThat(job.window().lastAvailable()).isBefore(job.window().d63());
        assertThat(job.window().completedTradingDays()).isEqualTo(50);
        assertThat(job.inputs().portfolioSeries()).hasSize(51);
        assertThat(job.universe().items())
                .filteredOn(item -> item.delistedOn() != null)
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.instrumentId()).isEqualTo("244580.XKRX");
                            assertThat(item.delistedOn()).hasToString("2012-06-11");
                        });
    }

    @Test
    void 시작_이벤트() throws IOException {
        RegimeLinkEvent<RegimeLinkStartedPayload> event = roundTrip("event-started.json", STARTED);

        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.type()).isEqualTo(RegimeLinkEventType.STARTED);
        assertThat(event.linkId()).isEqualTo("RL-20260918-000101");
        assertThat(event.payload().startedAt()).hasToString("2026-09-18T02:00:00Z");
        assertThat(event.payload().resource().pid()).isEqualTo(4242);
    }

    @Test
    void 완료_이벤트() throws IOException {
        RegimeLinkEvent<RegimeLinkFinishedPayload> event =
                roundTrip("event-finished-completed.json", FINISHED);
        RegimeLinkFinishedPayload payload = event.payload();
        RegimeLinkResultPayload result = payload.result();

        assertThat(payload.status()).isEqualTo(RegimeLinkStatus.COMPLETED);
        assertThat(payload.partial()).isFalse();
        assertThat(payload.delistedOn()).isNull();
        assertThat(payload.error()).isNull();
        assertThat(result.window63().complete()).isTrue();
        assertThat(result.window63().tradingDays()).isEqualTo(63);
        assertThat(result.window63().turnoverBasis()).isEqualTo(TurnoverBasis.ANNUALIZED_252);
        // 시계열과 목록은 구간 전체를 담는다
        assertThat(result.holdings()).hasSize(result.window63().tradingDays());
        assertThat(result.trades()).hasSize(result.window63().tradeCount());
        // 이익 종목도 빠지지 않는다 — 보유 섹터 셋이 모두 들어간다
        assertThat(result.window63().lossContribution())
                .containsOnlyKeys("SEMICONDUCTOR", "BATTERY", "BIO");
        assertThat(result.kospi().window63().returnRate()).isEqualByComparingTo("0.0180");
        assertThat(result.basis().kind()).isEqualTo(BasisKind.HISTORICAL_REVISION);
    }

    @Test
    void 육십삼거래일을_못_채운_완료_이벤트() throws IOException {
        RegimeLinkEvent<RegimeLinkFinishedPayload> event =
                roundTrip("event-finished-incomplete63.json", FINISHED);
        RegimeLinkFinishedPayload payload = event.payload();

        // 계산은 끝났고(COMPLETED) 63을 못 채웠을 뿐이다
        assertThat(payload.status()).isEqualTo(RegimeLinkStatus.COMPLETED);
        assertThat(payload.partial()).isTrue();
        assertThat(payload.delistedOn()).hasToString("2012-06-11");
        assertThat(payload.result().window63().complete()).isFalse();
        assertThat(payload.result().window63().tradingDays()).isEqualTo(50);
        // window21은 언제나 완성이다 — 21 미만이면 애초에 발행되지 않는다
        assertThat(payload.result().window21().complete()).isTrue();
        assertThat(payload.result().window21().tradingDays()).isEqualTo(21);
    }

    @Test
    void 실패_이벤트() throws IOException {
        RegimeLinkEvent<RegimeLinkFinishedPayload> event =
                roundTrip("event-finished-failed.json", FINISHED);
        RegimeLinkFinishedPayload payload = event.payload();

        assertThat(payload.status()).isEqualTo(RegimeLinkStatus.FAILED);
        assertThat(payload.result()).isNull();
        assertThat(payload.error().code()).isEqualTo(RegimeLinkErrorCode.INPUT_INCOMPLETE);
        assertThat(payload.resource().wallSeconds()).isEqualByComparingTo("0.12");
    }

    @Test
    void 직렬화_표기_규약() throws IOException {
        JsonNode out =
                mapper.readTree(
                        mapper.writeValueAsString(read("event-finished-completed.json", FINISHED)));
        JsonNode window63 = out.at("/payload/result/window63");

        // 계약이 정한 키는 return이다 — 예약어라 record 컴포넌트는 returnRate이고 mixin이 이름을 맞춘다
        assertThat(window63.has("returnRate")).isFalse();
        assertThat(window63.get("return").asText()).isEqualTo("0.0276");
        assertThat(out.at("/payload/result/kospi/window21/return").isTextual()).isTrue();
        assertThat(window63.get("tradingDays").isInt()).isTrue(); // 거래일 수는 숫자
        assertThat(window63.get("turnover").isTextual()).isTrue(); // 소수는 문자열
        assertThat(out.get("occurredAt").asText()).endsWith("Z"); // Instant → UTC ISO
        assertThat(out.at("/payload").has("delistedOn")).isFalse(); // null 키 생략
        assertThat(out.at("/payload").has("error")).isFalse();
    }

    @Test
    void 작업_본문에는_schemaVersion이_없다() throws IOException {
        // 작업은 AMQP 헤더로만 버전을 싣는다(계약 "표기 규칙")
        assertThat(mapper.readTree(raw("job-continuous.json")).has("schemaVersion")).isFalse();
        assertThat(mapper.readTree(raw("event-started.json")).has("schemaVersion")).isTrue();
    }

    @Test
    void 필수_정수_누락은_실패한다() {
        String noAttempt =
                """
                {"schemaVersion":1,"eventId":"E","linkId":"RL-1","type":"STARTED",
                 "occurredAt":"2026-09-18T02:00:00Z","payload":{"startedAt":"2026-09-18T02:00:00Z"}}
                """;
        assertThatThrownBy(() -> mapper.readValue(noAttempt, STARTED))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void 미지의_enum_값은_실패한다() {
        String badType =
                """
                {"schemaVersion":1,"eventId":"E","linkId":"RL-1","attempt":1,"type":"PROGRESS",
                 "occurredAt":"2026-09-18T02:00:00Z","payload":{"startedAt":"2026-09-18T02:00:00Z"}}
                """;
        assertThatThrownBy(() -> mapper.readValue(badType, STARTED))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void 정수_자리의_소수는_실패한다() {
        String floatQuantity =
                """
                {"job":{"linkId":"RL-1","attempt":1,"scenario":"CONTINUOUS"},
                 "inputs":{"openingPositions":[{"instrumentId":"091160.XKRX","quantity":1.5}]}}
                """;
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        floatQuantity, new TypeReference<RegimeLinkJob>() {}))
                .isInstanceOf(MismatchedInputException.class);
    }

    private <T> T roundTrip(String file, TypeReference<T> type) throws IOException {
        String original = raw(file);
        T value = mapper.readValue(original, type);
        JsonNode written = mapper.readTree(mapper.writeValueAsString(value));
        assertThat(written).as(file).isEqualTo(mapper.readTree(original));
        return value;
    }

    private <T> T read(String file, TypeReference<T> type) throws IOException {
        return mapper.readValue(raw(file), type);
    }

    private static String raw(String file) throws IOException {
        return Files.readString(FIXTURES.resolve(file));
    }
}
