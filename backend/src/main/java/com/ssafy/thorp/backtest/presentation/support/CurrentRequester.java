package com.ssafy.thorp.backtest.presentation.support;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 요청자 식별. spec 004 R-12는 모든 백테스트 엔드포인트에 인증을 요구하고 {@code backtest_request.requester_id}는 NOT NULL이다.
 *
 * <p>인증 주체는 spec 014 로그인이 세션에 넣어 둔 {@code users.id}다({@code
 * UsernamePasswordAuthenticationToken.authenticated(String.valueOf(user.getId()), …)}). 그 형태가 아니면
 * 우리가 만든 세션이 아니므로 로그인 상태로 보지 않는다.
 *
 * <p>백테스트 경로는 {@code SecurityConfig}에서 {@code permitAll}이라 익명 요청도 컨트롤러까지 온다. 그래서 인증 요구는 여기서 한다 —
 * {@link #requireRequesterId}가 던지는 401은 {@code GlobalExceptionHandler}를 거쳐 {@code SecurityConfig}의
 * 진입점이 내는 것과 같은 봉투가 된다.
 */
@Component
public class CurrentRequester {

    /**
     * 요청자를 찾되 없으면 비어 있다. 소유자 필터가 아직 없는 경로(spec 007 자동 탐색, research R4)가 쓴다.
     *
     * <p>{@link #requireRequesterId}와 갈라 두는 이유: 남의 결과를 막아야 하는 조회는 요청자가 없으면 진행하면 안 되고, 소유자를 기록만 하는
     * 경로는 없다고 막을 근거가 없다.
     */
    public Optional<Long> findRequesterId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(authentication.getName()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public long requireRequesterId() {
        return findRequesterId()
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }
}
