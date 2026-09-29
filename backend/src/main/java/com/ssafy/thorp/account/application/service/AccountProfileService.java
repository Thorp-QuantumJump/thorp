package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.command.ChangeNicknameCommand;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원정보 수정. 지금 바꿀 수 있는 것은 닉네임 하나다.
 *
 * <p>이메일은 가입 때 인증한 값이라 여기서 바꾸지 않고, 휴대폰 번호는 현재 사용자 응답에 싣지 않으므로 화면에서 다루지 않는다. 닉네임은 유일하지 않다 — 가입도 중복을
 * 막지 않는다.
 */
@Service
public class AccountProfileService {

    private final UserRepository userRepository;

    public AccountProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 닉네임을 바꾸고 바뀐 현재 사용자를 돌려준다.
     *
     * <p>활성 계정만 바꾼다. 세션이 남아 있어도 정지된 계정은 현재 사용자 조회와 같이 로그인하지 않은 것으로 본다.
     */
    @Transactional
    public AuthenticatedUserResult changeNickname(ChangeNicknameCommand command) {
        User user =
                userRepository
                        .findById(command.userId())
                        .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));

        userRepository.updateNickname(user.getId(), command.nickname());

        return new AuthenticatedUserResult(
                user.getId(), user.getEmail(), command.nickname(), user.getStatus().name());
    }
}
