package com.ssafy.thorp.account.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<UserEntity> findByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * 마지막 로그인 시각 컬럼 하나만 쓰는 UPDATE.
     *
     * <p>엔티티를 읽어 필드를 바꾸는 방식으로 하지 않는다. Hibernate 기본 UPDATE는 바꾸지 않은 컬럼까지 함께 써서, 영속성 컨텍스트에 올라온 시점의 옛
     * 값이 그대로 나간다.
     */
    @Modifying
    @Query("update UserEntity u set u.lastLoginAt = :lastLoginAt where u.id = :id")
    int updateLastLoginAt(@Param("id") Long id, @Param("lastLoginAt") Instant lastLoginAt);

    /**
     * 닉네임 컬럼과 수정 시각만 쓰는 UPDATE. JPQL UPDATE는 {@code @PreUpdate}를 거치지 않으므로 수정 시각을 함께 넘긴다.
     *
     * <p>{@code clearAutomatically}로 영속성 컨텍스트를 비운다. 같은 트랜잭션에서 방금 읽은 엔티티가 남아 있으면 이후 조회가 옛 닉네임을 본다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
            "update UserEntity u set u.nickname = :nickname, u.updatedAt = :updatedAt"
                    + " where u.id = :id")
    int updateNickname(
            @Param("id") Long id,
            @Param("nickname") String nickname,
            @Param("updatedAt") Instant updatedAt);
}
