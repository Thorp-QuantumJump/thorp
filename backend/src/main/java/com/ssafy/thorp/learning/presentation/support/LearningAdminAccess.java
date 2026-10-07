package com.ssafy.thorp.learning.presentation.support;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.application.port.LearningAdminPolicy;
import org.springframework.stereotype.Component;

/**
 * 배우기 관리 요청의 관문(learning-admin-api §0). 관리 컨트롤러의 모든 메서드가 첫 줄에서 {@link #requireAdminUserId()}를 부른다.
 *
 * <p>로그인 주체는 {@link LearnerAccess}가 읽는다. 배우기의 두 관문(관리·나의 학습)이 같은 규칙으로 {@code users.id}를 얻고 같은 401로
 * 답하게 하려는 것이다. 004 {@code CurrentRequester}를 import하지 않는 이유는 {@code learning → backtest} 의존을 만들지 않기
 * 위해서다.
 *
 * <p><b>014가 역할을 제공하면 이 클래스와 {@code ConfiguredLearningAdminPolicy}만 바꾼다.</b>
 */
@Component
public class LearningAdminAccess {

    private final LearningAdminPolicy policy;
    private final LearnerAccess learnerAccess;

    public LearningAdminAccess(LearningAdminPolicy policy, LearnerAccess learnerAccess) {
        this.policy = policy;
        this.learnerAccess = learnerAccess;
    }

    /**
     * @return 관리 계정의 사용자 식별자. 게시 기록에 남는다(FR-018)
     * @throws BusinessException 로그인하지 않았으면 401 {@code AUTHENTICATION_REQUIRED}, 관리 권한이 없으면 404
     *     {@code RESOURCE_NOT_FOUND}. 403으로 답하면 관리 경로가 있다는 사실이 드러난다(research R6)
     */
    public long requireAdminUserId() {
        long userId = learnerAccess.requireUserId();
        if (!policy.isAdmin(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return userId;
    }
}
