package com.ssafy.thorp.market.application.service;

import com.ssafy.thorp.market.application.port.MarketDeadline;
import com.ssafy.thorp.market.application.port.MarketProvider;
import com.ssafy.thorp.market.application.result.MarketResult;
import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class MarketService {
    private final List<MarketProvider> providers;
    private final Clock clock;
    private final MarketCachePolicy policy;
    private final Map<MarketCode, Slot> slots = new EnumMap<>(MarketCode.class);

    public MarketService(
            List<MarketProvider> providers,
            @Qualifier("marketClock") Clock clock,
            MarketCachePolicy policy) {
        this.providers = List.copyOf(providers);
        this.clock = clock;
        this.policy = policy;
        for (MarketCode code : MarketCode.values()) slots.put(code, new Slot());
    }

    public MarketResult get(MarketCode code) {
        MarketDeadline deadline = new MarketDeadline(policy.requestBudget());
        Slot slot = slots.get(code);
        boolean locked = false;
        try {
            locked = slot.lock.tryLock(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
            if (!locked) throw new MarketDataException(MarketDataException.Reason.TIMEOUT);
            Instant now = clock.instant();
            if (slot.value != null && now.isBefore(slot.retrievedAt.plus(policy.ttl())))
                return new MarketResult(code, slot.value, slot.retrievedAt, false);
            if (slot.failure != null && now.isBefore(slot.retryAfter))
                return fallback(code, slot, now);
            MarketProvider provider =
                    providers.stream()
                            .filter(p -> p.supports(code))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new MarketDataException(
                                                    MarketDataException.Reason.NOT_CONFIGURED));
            try {
                MarketObservation observation = provider.fetch(code, deadline);
                slot.value = observation;
                slot.retrievedAt = clock.instant();
                slot.failure = null;
                return new MarketResult(code, observation, slot.retrievedAt, false);
            } catch (MarketDataException e) {
                slot.failure = e;
                slot.retryAfter = clock.instant().plus(policy.failureCooldown());
                return fallback(code, slot, clock.instant());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException(MarketDataException.Reason.TIMEOUT);
        } finally {
            if (locked) slot.lock.unlock();
        }
    }

    private MarketResult fallback(MarketCode code, Slot slot, Instant now) {
        if (slot.value != null && now.isBefore(slot.retrievedAt.plus(policy.maxAge())))
            return new MarketResult(code, slot.value, slot.retrievedAt, true);
        slot.value = null;
        throw slot.failure;
    }

    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        MarketObservation value;
        Instant retrievedAt;
        Instant retryAfter;
        MarketDataException failure;
    }
}
