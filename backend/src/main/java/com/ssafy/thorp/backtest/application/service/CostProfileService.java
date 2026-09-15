package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.repository.CostProfileRepository;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비용 조건 등록표 조회(spec 004 research R-07).
 *
 * <p>요청이 버전을 지정하지 않으면 서버 기본값을 쓴다. 기본값 자체는 팀 결정(PD-07) 전까지 임시라 {@link CostProfile#provisional()}가
 * true이고, 결과에 그대로 표시된다.
 */
@Service
public class CostProfileService {

    private final CostProfileRepository repository;
    private final String defaultVersion;

    public CostProfileService(
            CostProfileRepository repository,
            @Value("${thorp.backtest.default-cost-profile:PROVISIONAL-DEMO-1}")
                    String defaultVersion) {
        this.repository = repository;
        this.defaultVersion = defaultVersion;
    }

    /**
     * 실행에 쓸 비용 조건. {@code version}이 null이면 서버 기본값을 쓴다.
     *
     * <p>지정한 버전이 등록표에 없으면 입력 오류다(400). 서버 기본값이 없으면 설정과 마이그레이션이 어긋난 것이라 500으로 올린다 — 사용자가 고칠 수 있는 일이
     * 아니다.
     */
    // noRollbackFor: 접수 트랜잭션(noRollbackFor) 안에서 불린다. 여기서 던지는 거절이 바깥을 rollback-only 로 표시하면
    // 커밋 시점에 UnexpectedRollbackException(500) 이 된다 — StrategyRulesService 와 같은 이유(T013 S9).
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public CostProfile resolve(String version) {
        if (version == null || version.isBlank()) {
            return repository
                    .find(defaultVersion)
                    .orElseThrow(
                            () ->
                                    new BusinessException(
                                            ErrorCode.INTERNAL_ERROR,
                                            "default cost profile is not registered: "
                                                    + defaultVersion));
        }
        return repository
                .find(version)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INVALID_INPUT,
                                        "unknown cost profile version: " + version));
    }

    /** 요청이 버전을 비웠을 때 실행에 기록할 버전 문자열. */
    public String defaultVersion() {
        return defaultVersion;
    }
}
