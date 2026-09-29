package com.ssafy.thorp.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.account.application.command.ChangeNicknameCommand;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.application.service.AccountProfileService;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 닉네임 변경이 활성 계정의 닉네임 컬럼만 쓰고, 바뀐 현재 사용자를 돌려주는지 고정한다. */
class AccountProfileServiceTest {

    private static final long USER_ID = 7L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccountProfileService service = new AccountProfileService(userRepository);

    @Test
    @DisplayName("활성 계정은 닉네임만 갱신하고 바뀐 닉네임으로 현재 사용자를 돌려준다")
    void changesNicknameOfActiveUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.ACTIVE)));

        AuthenticatedUserResult result =
                service.changeNickname(new ChangeNicknameCommand(USER_ID, "새닉네임"));

        verify(userRepository).updateNickname(USER_ID, "새닉네임");
        verify(userRepository, never()).save(any());
        assertThat(result)
                .isEqualTo(
                        new AuthenticatedUserResult(
                                USER_ID, "member@example.com", "새닉네임", "ACTIVE"));
    }

    @Test
    @DisplayName("세션의 식별자에 해당하는 계정이 없으면 로그인이 필요하다고 거절한다")
    void rejectsMissingUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeNickname(new ChangeNicknameCommand(USER_ID, "새닉네임")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
        verify(userRepository, never()).updateNickname(anyLong(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("활성이 아닌 계정은 세션이 남아 있어도 닉네임을 바꾸지 못한다")
    void rejectsInactiveUser(UserStatus status) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(status)));

        assertThatThrownBy(() -> service.changeNickname(new ChangeNicknameCommand(USER_ID, "새닉네임")))
                .isInstanceOf(BusinessException.class);
        verify(userRepository, never()).updateNickname(anyLong(), anyString());
    }

    private static User user(UserStatus status) {
        return new User(
                USER_ID,
                "member@example.com",
                "password-hash",
                "옛닉네임",
                "01012345678",
                null,
                status,
                false,
                false,
                false,
                null,
                null,
                null,
                null);
    }
}
