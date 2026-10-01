package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.domain.model.SocialAccount;

public class SocialAccountMapper {

    private SocialAccountMapper() {}

    public static SocialAccountEntity toEntity(SocialAccount account) {
        return new SocialAccountEntity(
                account.getId(),
                account.getUserId(),
                account.getProvider(),
                account.getProviderUserId(),
                account.getLinkedAt());
    }

    public static SocialAccount toDomain(SocialAccountEntity entity) {
        return new SocialAccount(
                entity.getId(),
                entity.getUserId(),
                entity.getProvider(),
                entity.getProviderUserId(),
                entity.getLinkedAt());
    }
}
