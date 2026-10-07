package com.ssafy.thorp.learning.presentation.support;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.common.security.SessionUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 나의 학습 요청의 사용자(learning-me-api §0, research R33). 나의 학습 컨트롤러의 모든 메서드가 첫 줄에서 {@link
 * #requireUserId()}를 부른다.
 *
 * <p>{@code SecurityConfig}가 {@code /learning/me/**}를 {@code authenticated()}로 묶어 비로그인 요청은 여기 오기 전에
 * 401이다. 여기서 다시 읽는 것은 인증 종류까지 보는 {@link SessionUser} 규칙으로 {@code users.id}를 얻기 위해서다. 004 {@code
 * CurrentRequester}를 import하지 않는다. {@code learning → backtest} 의존을 만들지 않는다. 관리 관문({@link
 * LearningAdminAccess})도 이 클래스로 로그인 주체를 읽는다.
 */
@Component
public class LearnerAccess {

    /**
     * @return 로그인한 사용자의 {@code users.id}
     * @throws BusinessException 로그인 주체가 없거나 우리 세션이 아니면 401 {@code AUTHENTICATION_REQUIRED}
     */
    public long requireUserId() {
        return SessionUser.idOf(SecurityContextHolder.getContext().getAuthentication())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }
}
