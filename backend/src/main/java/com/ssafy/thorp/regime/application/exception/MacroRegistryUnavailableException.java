package com.ssafy.thorp.regime.application.exception;

/** 지표 등록표를 읽을 수 없다 — 008 미구현이거나 그 조회가 실패했다. */
public class MacroRegistryUnavailableException extends RuntimeException {

    public MacroRegistryUnavailableException(String message) {
        super(message);
    }

    public MacroRegistryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
