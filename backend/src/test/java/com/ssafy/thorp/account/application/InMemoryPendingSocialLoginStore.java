package com.ssafy.thorp.account.application;

import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import java.util.Optional;

/** 세션 하나를 흉내 내는 대기 상태 저장소. 단위 테스트가 서비스가 남긴 상태를 직접 본다. */
public class InMemoryPendingSocialLoginStore implements PendingSocialLoginStore {

    private PendingSocialLogin value;

    @Override
    public Optional<PendingSocialLogin> find() {
        return Optional.ofNullable(value);
    }

    @Override
    public void save(PendingSocialLogin pending) {
        value = pending;
    }

    @Override
    public void clear() {
        value = null;
    }

    public PendingSocialLogin current() {
        return value;
    }
}
