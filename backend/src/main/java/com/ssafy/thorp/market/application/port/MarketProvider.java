package com.ssafy.thorp.market.application.port;

import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;

public interface MarketProvider {
    boolean supports(MarketCode code);

    MarketObservation fetch(MarketCode code, MarketDeadline deadline);
}
