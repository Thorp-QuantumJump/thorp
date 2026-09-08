package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.domain.model.User;

public class UserMapper {

    private UserMapper() {}

    public static UserEntity toEntity(User user) {
        return new UserEntity(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getNickname(),
                user.getPhoneNumber(),
                user.getProfileImageUrl(),
                user.getStatus(),
                user.isPaperRankingEnabled(),
                user.isLiveRankingEnabled(),
                user.isRankingDisplayNickname(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getWithdrawnAt());
    }

    public static User toDomain(UserEntity entity) {
        return new User(
                entity.getId(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.getNickname(),
                entity.getPhoneNumber(),
                entity.getProfileImageUrl(),
                entity.getStatus(),
                entity.isPaperRankingEnabled(),
                entity.isLiveRankingEnabled(),
                entity.isRankingDisplayNickname(),
                entity.getLastLoginAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getWithdrawnAt());
    }
}
