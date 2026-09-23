package com.ssafy.thorp.market.infrastructure.client;

import com.ssafy.thorp.market.domain.exception.MarketDataException;
import com.ssafy.thorp.market.domain.exception.MarketDataException.Reason;
import com.ssafy.thorp.market.domain.model.MarketObservation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

final class MarketValues {
    private MarketValues() {}

    static Optional<MarketObservation> observation(
            String value, String unit, String period, boolean monthly) {
        if (value == null
                || value.isBlank()
                || value.equals("-")
                || value.equals(".")
                || value.equals("...")) return Optional.empty();
        try {
            String date =
                    monthly
                            ? YearMonth.parse(period, DateTimeFormatter.ofPattern("uuuuMM"))
                                    .toString()
                            : LocalDate.parse(period, DateTimeFormatter.BASIC_ISO_DATE).toString();
            return Optional.of(
                    new MarketObservation(
                            new BigDecimal(value.replace(",", "").trim()), unit, date));
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new MarketDataException(Reason.INVALID_RESPONSE);
        }
    }

    static void require(boolean condition) {
        if (!condition) throw new MarketDataException(Reason.INVALID_RESPONSE);
    }
}
