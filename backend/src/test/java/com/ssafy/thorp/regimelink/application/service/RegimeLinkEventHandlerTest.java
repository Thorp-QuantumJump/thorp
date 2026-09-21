package com.ssafy.thorp.regimelink.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.regimelink.application.port.message.BasisKind;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkErrorCode;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkEvent;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkEventType;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkFinishedPayload;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkResultPayload;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkStartedPayload;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkStatus;
import com.ssafy.thorp.regimelink.application.port.message.TurnoverBasis;
import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import com.ssafy.thorp.regimelink.infrastructure.messaging.JacksonRegimeLinkResultCodec;
import com.ssafy.thorp.regimelink.support.InMemoryLinkRepository;
import com.ssafy.thorp.regimelink.support.InMemoryResultRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 워커 이벤트의 상태 전이 (T032, T036 가운데 Docker 없이 볼 수 있는 몫).
 *
 * <p>여기서 보는 것은 전이 규칙이다 — 큐를 지나는 실제 전달은 통합 시험이 본다.
 */
@DisplayName("사례 연결 이벤트 반영")
class RegimeLinkEventHandlerTest {

    private static final String SNAPSHOT_KEY = "FIELD:LABOR:2026-07-01:DATA-1:REG-1:MODEL-1";
    private static final Instant NOW = Instant.parse("2026-09-21T03:00:00Z");

    private final InMemoryLinkRepository links = new InMemoryLinkRepository();
    private final InMemoryResultRepository results = new InMemoryResultRepository();
    private final RegimeLinkEventHandler handler =
            new RegimeLinkEventHandler(
                    links,
                    results,
                    new JacksonRegimeLinkResultCodec(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("STARTED 는 RUNNING 으로 옮기고 시작 시각을 남긴다")
    void 시작() {
        RegimeLink link = links.create(queued());
        handler.handleStarted(started(link, Instant.parse("2026-09-21T02:00:00Z")));

        RegimeLink after = links.findById(link.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(LinkStatus.RUNNING);
        assertThat(after.startedAt()).isEqualTo(Instant.parse("2026-09-21T02:00:00Z"));
    }

    @Test
    @DisplayName("FINISHED(COMPLETED) 는 결과를 저장하고 COMPLETED 로 끝낸다")
    void 완료() {
        RegimeLink link = links.create(queued());
        handler.handleFinished(finished(link, completed()));

        RegimeLink after = links.findById(link.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(LinkStatus.COMPLETED);
        assertThat(after.finishedAt()).isEqualTo(NOW);
        assertThat(results.findByLinkId(link.id())).isPresent();
    }

    @Test
    @DisplayName("구간 손익이 0 이라 생략된 lossContribution 은 키째로 빠져 저장된다")
    void 손익기여_생략() {
        RegimeLink link = links.create(queued());
        handler.handleFinished(finished(link, completed()));

        Map<String, Object> window63 = results.findByLinkId(link.id()).orElseThrow().window63();
        assertThat(window63).doesNotContainKey("lossContribution");
        // 계약이 정한 키 이름(return)과 문자열 decimal 표기가 그대로 저장된다.
        assertThat(window63).containsEntry("return", "0.0421");
    }

    @Test
    @DisplayName("종결된 링크에 늦게 온 이벤트는 상태를 되돌리지 않는다")
    void 늦은_이벤트() {
        RegimeLink link = links.create(queued());
        handler.handleFinished(finished(link, completed()));
        handler.handleStarted(started(link, Instant.parse("2026-09-21T04:00:00Z")));

        assertThat(links.findById(link.id()).orElseThrow().status())
                .isEqualTo(LinkStatus.COMPLETED);
    }

    @Test
    @DisplayName("DLQ 로 떨어진 작업은 FAILED(SYSTEM_FAILURE) 다")
    void 반송() {
        RegimeLink link = links.create(queued());
        handler.handleDeadLetter(link.linkId().value());

        RegimeLink after = links.findById(link.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(LinkStatus.FAILED);
        assertThat(after.failure().code()).isEqualTo(LinkFailure.SYSTEM_FAILURE);
    }

    @Test
    @DisplayName("실패 코드는 워커가 보낸 값 그대로 남는다")
    void 실패_코드() {
        RegimeLink link = links.create(queued());
        handler.handleFinished(
                finished(
                        link,
                        new RegimeLinkFinishedPayload(
                                RegimeLinkStatus.FAILED,
                                false,
                                null,
                                null,
                                new RegimeLinkFinishedPayload.ErrorDetail(
                                        RegimeLinkErrorCode.MARKET_DATA_MISSING, "가격 자료 불일치"),
                                null)));

        LinkFailure failure = links.findById(link.id()).orElseThrow().failure();
        assertThat(failure.code()).isEqualTo("MARKET_DATA_MISSING");
        // 첫 시도는 다시 시도할 수 있고, 두 번째부터는 같은 코드가 나와도 버튼을 두지 않는다(research R13).
        assertThat(failure.retryable(1)).isTrue();
        assertThat(failure.retryable(2)).isFalse();
    }

    @Test
    @DisplayName("모르는 링크의 이벤트는 아무것도 바꾸지 않는다")
    void 모르는_링크() {
        handler.handleDeadLetter("RL-20260921-999999");
        assertThat(links.size()).isZero();
    }

    // --- 도우미 -------------------------------------------------------------------

    private static RegimeLink queued() {
        return RegimeLink.pending(
                77L,
                new CaseRef(SNAPSHOT_KEY, YearMonth.of(2024, 3)),
                Scenario.CONTINUOUS,
                new PostWindow(
                        LocalDate.of(2024, 4, 1),
                        LocalDate.of(2024, 4, 29),
                        LocalDate.of(2024, 6, 27),
                        LocalDate.of(2024, 6, 27),
                        63,
                        true,
                        true),
                Availability.normal(63),
                LinkStatus.QUEUED);
    }

    private static RegimeLinkEvent<RegimeLinkStartedPayload> started(RegimeLink link, Instant at) {
        return new RegimeLinkEvent<>(
                1,
                "5f2b9e7a-0000-5000-8000-000000000001",
                link.linkId().value(),
                1,
                RegimeLinkEventType.STARTED,
                at,
                new RegimeLinkStartedPayload(at, null));
    }

    private static RegimeLinkEvent<RegimeLinkFinishedPayload> finished(
            RegimeLink link, RegimeLinkFinishedPayload payload) {
        return new RegimeLinkEvent<>(
                1,
                "5f2b9e7a-0000-5000-8000-000000000002",
                link.linkId().value(),
                1,
                RegimeLinkEventType.FINISHED,
                NOW,
                payload);
    }

    /** {@code lossContribution} 이 없는 결과. 구간 손익이 0 일 때 워커가 그 키를 생략한다. */
    private static RegimeLinkFinishedPayload completed() {
        RegimeLinkResultPayload.WindowCosts costs =
                new RegimeLinkResultPayload.WindowCosts(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        RegimeLinkResultPayload.WindowSummary window =
                new RegimeLinkResultPayload.WindowSummary(
                        63,
                        new BigDecimal("0.0421"),
                        new BigDecimal("-0.0587"),
                        6,
                        new BigDecimal("2.00"),
                        new BigDecimal("0.3125"),
                        TurnoverBasis.ANNUALIZED_252,
                        costs,
                        null,
                        true);
        RegimeLinkResultPayload result =
                new RegimeLinkResultPayload(
                        window,
                        window,
                        List.of(),
                        List.of(),
                        new RegimeLinkResultPayload.Benchmark(null, null),
                        new RegimeLinkResultPayload.Basis(
                                BasisKind.HISTORICAL_REVISION,
                                "KRX-2026-09-09",
                                "KRX_DAILY_20260915",
                                "ANALYTICS-1"),
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        return new RegimeLinkFinishedPayload(
                RegimeLinkStatus.COMPLETED, false, null, result, null, null);
    }
}
