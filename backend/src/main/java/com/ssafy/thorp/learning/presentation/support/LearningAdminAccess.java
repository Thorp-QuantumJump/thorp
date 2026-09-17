package com.ssafy.thorp.learning.presentation.support;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.application.port.LearningAdminPolicy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 배우기 관리 요청의 관문(learning-admin-api §0). 관리 컨트롤러의 모든 메서드가 첫 줄에서 {@link #requireAdminUserId()}를 부른다.
 *
 * <p>인증 주체는 004 {@code CurrentRequester}와 같은 규약으로 읽는다. spec 014 로그인이 세션에 넣은 {@code users.id} 문자열이고,
 * 그 형태가 아니면 로그인으로 보지 않는다. {@code CurrentRequester}를 import하지 않는 이유는 {@code learning → backtest} 의존을
 * 만들지 않기 위해서다.
 *
 * <p><b>014가 역할을 제공하면 이 클래스와 {@code ConfiguredLearningAdminPolicy}만 바꾼다.</b>
 */
@Component
public class LearningAdminAccess {

    private final LearningAdminPolicy policy;

    public LearningAdminAccess(LearningAdminPolicy policy) {
        this.policy = policy;
    }

    /**
     * @return 관리 계정의 사용자 식별자. 게시 기록에 남는다(FR-018)
     * @throws BusinessException 로그인하지 않았으면 401 {@code AUTHENTICATION_REQUIRED}, 관리 권한이 없으면 404
     *     {@code RESOURCE_NOT_FOUND}. 403으로 답하면 관리 경로가 있다는 사실이 드러난다(research R6)
     */
    public long requireAdminUserId() {
        long userId = requireUserId();
        if (!policy.isAdmin(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return userId;
    }

    private static long requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException notOurSession) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
