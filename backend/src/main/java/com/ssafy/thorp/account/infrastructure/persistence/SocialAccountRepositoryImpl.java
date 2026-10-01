package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.application.repository.SocialAccountRepository;
import com.ssafy.thorp.account.domain.model.SocialAccount;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class SocialAccountRepositoryImpl implements SocialAccountRepository {

    private final SocialAccountJpaRepository jpaRepository;

    public SocialAccountRepositoryImpl(SocialAccountJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<SocialAccount> findByProviderAndProviderUserId(
            SocialProvider provider, String providerUserId) {
        return jpaRepository
                .findByProviderAndProviderUserId(provider, providerUserId)
                .map(SocialAccountMapper::toDomain);
    }

    @Override
    public List<SocialAccount> findByUserId(Long userId) {
        return jpaRepository.findByUserId(userId).stream()
                .map(SocialAccountMapper::toDomain)
                .toList();
    }

    /** 제약 위반이 이 메서드 안에서 드러나도록 즉시 flush한다. 어느 제약에 걸렸는지는 밖에 구분하지 않는다. */
    @Override
    public SocialAccount save(SocialAccount socialAccount) {
        try {
            return SocialAccountMapper.toDomain(
                    jpaRepository.saveAndFlush(SocialAccountMapper.toEntity(socialAccount)));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED);
        }
    }
}
