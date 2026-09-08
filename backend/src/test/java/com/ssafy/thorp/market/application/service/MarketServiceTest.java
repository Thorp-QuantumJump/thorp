package com.ssafy.thorp.market.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ssafy.thorp.market.application.port.MarketProvider;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketServiceTest {
    private final Instant collected = Instant.parse("2026-09-07T00:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final MarketProvider provider = mock(MarketProvider.class);
    private final MarketObservation value =
            new MarketObservation(new BigDecimal("1355.2"), "원/USD", "2026-09-07");
    private MarketService service;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(collected);
        when(provider.supports(any())).thenReturn(true);
        when(provider.fetch(any(), any())).thenReturn(value);
        service =
                new MarketService(
                        List.of(provider),
                        clock,
                        new MarketCachePolicy(
                                Duration.ofHours(6),
                                Duration.ofHours(24),
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(8)));
    }

    @Test
    void validCacheDoesNotCallProviderAgain() {
        service.get(MarketCode.USD_KRW);
        when(clock.instant()).thenReturn(collected.plusSeconds(21599));
        assertThat(service.get(MarketCode.USD_KRW).stale()).isFalse();
        verify(provider, times(1)).fetch(any(), any());
    }

    @Test
    void expiredCacheFallsBackWithoutChangingCollectionDate() {
        service.get(MarketCode.USD_KRW);
        when(clock.instant()).thenReturn(collected.plus(Duration.ofHours(6)));
        when(provider.fetch(any(), any()))
                .thenThrow(new MarketDataException(Reason.AUTHENTICATION));
        var result = service.get(MarketCode.USD_KRW);
        assertThat(result.stale()).isTrue();
        assertThat(result.retrievedAt()).isEqualTo(collected);
        assertThat(result.observation()).isEqualTo(value);
        service.get(MarketCode.USD_KRW);
        verify(provider, times(2)).fetch(any(), any());
    }

    @Test
    void oldValueIsNeverReturnedAtMaxAge() {
        service.get(MarketCode.USD_KRW);
        when(clock.instant()).thenReturn(collected.plus(Duration.ofHours(24)));
        when(provider.fetch(any(), any())).thenThrow(new MarketDataException(Reason.TIMEOUT));
        assertThatThrownBy(() -> service.get(MarketCode.USD_KRW))
                .isInstanceOf(MarketDataException.class);
    }

    @Test
    void failureIsIsolatedByCodeAndHasCooldown() {
        when(provider.fetch(eq(MarketCode.KOSPI), any()))
                .thenThrow(new MarketDataException(Reason.AUTHENTICATION));
        assertThatThrownBy(() -> service.get(MarketCode.KOSPI))
                .isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> service.get(MarketCode.KOSPI))
                .isInstanceOf(MarketDataException.class);
        assertThat(service.get(MarketCode.USD_KRW).observation()).isEqualTo(value);
        verify(provider, times(1)).fetch(eq(MarketCode.KOSPI), any());
        when(clock.instant()).thenReturn(collected.plusSeconds(30));
        when(provider.fetch(eq(MarketCode.KOSPI), any())).thenReturn(value);
        assertThat(service.get(MarketCode.KOSPI).stale()).isFalse();
    }

    @Test
    void slowCodeDoesNotBlockAnotherCodeAndSameCodeSharesRefresh() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(provider.fetch(eq(MarketCode.KOSPI), any()))
                .thenAnswer(
                        call -> {
                            started.countDown();
                            if (!release.await(3, TimeUnit.SECONDS))
                                throw new AssertionError("test latch timed out");
                            return value;
                        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.get(MarketCode.KOSPI));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.get(MarketCode.KOSPI));
            var independent = executor.submit(() -> service.get(MarketCode.USD_KRW));
            try {
                assertThat(independent.get(1, TimeUnit.SECONDS).observation()).isEqualTo(value);
            } finally {
                release.countDown();
            }
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            verify(provider, times(1)).fetch(eq(MarketCode.KOSPI), any());
        }
    }
}
