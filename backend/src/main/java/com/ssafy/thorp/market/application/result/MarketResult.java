package com.ssafy.thorp.market.application.result;

import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import java.time.Instant;

public record MarketResult(
        MarketCode code, MarketObservation observation, Instant retrievedAt, boolean stale) {}
