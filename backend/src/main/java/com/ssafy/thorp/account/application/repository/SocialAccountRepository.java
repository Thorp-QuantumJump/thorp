package com.ssafy.thorp.account.application.repository;

import com.ssafy.thorp.account.domain.model.SocialAccount;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.List;
import java.util.Optional;

public interface SocialAccountRepository {

    Optional<SocialAccount> findByProviderAndProviderUserId(
            SocialProvider provider, String providerUserId);

    List<SocialAccount> findByUserId(Long userId);

    /**
     * 연결을 저장한다.
     *
     * <p>사전 검사를 통과한 동시 요청 둘이 같은 소셜 계정이나 같은 (계정, 제공자)를 연결할 수 있어 최종 방어는 DB 고유 제약이 한다. 제약에 걸리면 {@code
     * SOCIAL_ACCOUNT_ALREADY_LINKED}로 바꾼다.
     */
    SocialAccount save(SocialAccount socialAccount);
}
