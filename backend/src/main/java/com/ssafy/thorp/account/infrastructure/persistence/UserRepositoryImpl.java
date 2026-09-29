package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpaRepository;

    public UserRepositoryImpl(UserJpaRepository userJpaRepository) {
        this.userJpaRepository = userJpaRepository;
    }

    /**
     * 사용자를 저장한다.
     *
     * <p>사전 중복 검사를 통과한 동시 요청 둘이 같은 이메일·전화번호로 들어올 수 있어, 최종 방어는 DB 유니크 제약이 한다. 제약 위반이 이 메서드 안에서 드러나도록
     * {@code saveAndFlush}로 즉시 flush하고, 어느 컬럼이 걸렸는지는 구분하지 않고 클라이언트 오류로 바꾼다.
     */
    @Override
    public User save(User user) {
        UserEntity entity = UserMapper.toEntity(user);

        try {
            UserEntity savedEntity = userJpaRepository.saveAndFlush(entity);
            return UserMapper.toDomain(savedEntity);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 사용 중인 이메일 또는 전화번호입니다.");
        }
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return userJpaRepository.findByEmail(email).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findById(Long id) {
        return userJpaRepository.findById(id).map(UserMapper::toDomain);
    }

    @Override
    public void updateLastLoginAt(Long id, Instant lastLoginAt) {
        userJpaRepository.updateLastLoginAt(id, lastLoginAt);
    }

    @Override
    public void updateNickname(Long id, String nickname) {
        userJpaRepository.updateNickname(id, nickname, Instant.now());
    }

    @Override
    public boolean existsByEmail(String email) {
        return userJpaRepository.existsByEmail(email);
    }

    @Override
    public boolean existsByPhoneNumber(String phoneNumber) {
        return userJpaRepository.existsByPhoneNumber(phoneNumber);
    }
}
