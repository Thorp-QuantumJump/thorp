package com.ssafy.thorp.result.presentation.request;

import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * {@code GET /results/{token}/trades} 질의. 004 {@code TradePageQuery}와 같은 필드다.
 *
 * <p><b>{@code size}에 최댓값 검증을 두지 않는다.</b> 공개 경로는 상한을 넘는 크기를 400으로 거절하지 않고 설정 상한으로 자른다(계약 "경로 대응").
 * 004 DTO를 그대로 쓰면 그쪽 {@code @Max}가 먼저 걸려 큰 값이 400이 되고, 설정 상한을 그 값보다 크게 두면 상한까지도 쓸 수 없다.
 */
public record PublicTradePageQuery(
        @Min(0) Integer page,
        @Min(1) Integer size,
        String instrumentId,
        TradeStatus status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

    public int pageOrDefault() {
        return page == null ? 0 : page;
    }
}
