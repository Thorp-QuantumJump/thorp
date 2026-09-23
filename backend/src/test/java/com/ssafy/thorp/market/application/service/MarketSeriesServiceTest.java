package com.ssafy.thorp.market.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ssafy.thorp.market.application.port.MarketSeriesProvider;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketSeriesServiceTest {
    private final Clock clock = mock(Clock.class);
    private final MarketSeriesProvider provider = mock(MarketSeriesProvider.class);
    private MarketSeriesService service;

    private static MarketObservation point(String date, String value) {
        return new MarketObservation(new BigDecimal(value), "pt", date);
    }

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-09T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(i -> Clock.fixed(clock.instant(), i.getArgument(0)));
        when(provider.supportsSeries(any())).thenReturn(true);
        service =
                new MarketSeriesService(
                        List.of(provider),
                        clock,
                        new MarketSeriesPolicy(3, 30, Duration.ofSeconds(5), Duration.ofHours(24)));
    }

    @Test
    void noCollectedValueIsNoDataWithoutCallingUpstream() {
        assertThatThrownBy(() -> service.get(MarketCode.KOSPI))
                .isInstanceOfSatisfying(
                        MarketDataException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NO_DATA));
        verifyNoInteractions(provider);
    }

    @Test
    void skipsKnownDatesAndKeepsOnlyTheLatestWindow() {
        when(provider.fetchSeries(eq(MarketCode.KOSPI), any(), any(), any(), any()))
                .thenReturn(List.of(point("2026-09-03", "1"), point("2026-09-04", "2")))
                .thenReturn(List.of(point("2026-09-07", "3"), point("2026-09-08", "4")));

        service.refresh(MarketCode.KOSPI);
        service.refresh(MarketCode.KOSPI);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> known = ArgumentCaptor.forClass(Set.class);
        verify(provider, times(2))
                .fetchSeries(eq(MarketCode.KOSPI), any(), any(), known.capture(), any());
        assertThat(known.getAllValues().get(1))
                .containsExactlyInAnyOrder("2026-09-03", "2026-09-04");
        var result = service.get(MarketCode.KOSPI);
        assertThat(result.points())
                .extracting(MarketObservation::observationDate)
                .containsExactly("2026-09-04", "2026-09-07", "2026-09-08");
        assertThat(result.stale()).isFalse();
    }

    @Test
    void failedRefreshKeepsCollectedValuesAndMarksThemStaleWhenOld() {
        when(provider.fetchSeries(any(), any(), any(), any(), any()))
                .thenReturn(List.of(point("2026-09-08", "10")))
                .thenThrow(new MarketDataException(Reason.UPSTREAM_ERROR));
        service.refresh(MarketCode.KOSPI);

        when(clock.instant()).thenReturn(Instant.parse("2026-09-10T06:00:00Z"));
        service.refreshAll(); // 실패는 기록만 하고 삼킨다

        var result = service.get(MarketCode.KOSPI);
        assertThat(result.points())
                .extracting(MarketObservation::value)
                .containsExactly(new BigDecimal("10"));
        assertThat(result.stale()).isTrue();
    }

    @Test
    void codesOutsideTheLandingChartHaveNoSeries() {
        assertThatThrownBy(() -> service.get(MarketCode.M2))
                .isInstanceOfSatisfying(
                        MarketDataException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
    }
}
