package com.ssafy.thorp.account.application.service;

import java.security.SecureRandom;

/** 이메일로 보내는 6자리 인증번호. 이메일 가입(FR-004)과 소셜 경로(FR-022·FR-024)가 같은 규칙을 쓴다. */
final class VerificationCodes {

    private static final int CODE_BOUND = 1_000_000;
    private static final String CODE_FORMAT = "%06d";
    private static final SecureRandom RANDOM = new SecureRandom();

    private VerificationCodes() {}

    static String next() {
        return String.format(CODE_FORMAT, RANDOM.nextInt(CODE_BOUND));
    }
}
