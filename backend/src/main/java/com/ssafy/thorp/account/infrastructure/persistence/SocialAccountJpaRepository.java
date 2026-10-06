package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountJpaRepository extends JpaRepository<SocialAccountEntity, Long> {

    Optional<SocialAccountEntity> findByProviderAndProviderUserId(
            SocialProvider provider, String providerUserId);

    List<SocialAccountEntity> findByUserId(Long userId);
}
