package com.ssafy.thorp.account.domain.model;

public enum UserStatus {
    PENDING_VERIFICATION, // 미 인증상태
    ACTIVE,
    SUSPENDED, // 정지계정
    WITHDRAWN
}
