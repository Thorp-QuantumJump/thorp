package com.ssafy.thorp.regimelink.application.port;

/**
 * 작업 메시지의 크기 상한 (계약 "크기 상한", T031).
 *
 * <p>넘으면 발행하지 않고 링크를 {@code FAILED(INPUT_TOO_LARGE)}로 끝낸다 — 브로커에 넣어 놓고 워커가 거절하게 두면 같은 실패가 재전달 상한만큼
 * 반복된다.
 *
 * @param maxRows {@code inputs.portfolioSeries}의 날짜 행 수 상한(사후 63거래일 + 기준일 한 행)
 * @param maxInstruments 보유 스냅샷 한 건의 종목 수 상한(대응표 섹터 수)
 */
public record JobSizeLimits(int maxRows, int maxInstruments) {}
