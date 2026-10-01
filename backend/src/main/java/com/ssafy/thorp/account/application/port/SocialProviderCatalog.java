package com.ssafy.thorp.account.application.port;

import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.List;

/** 자격 증명이 설정되어 실제로 쓸 수 있는 제공자 목록. 설정되지 않은 제공자는 버튼도 시작 경로도 없다. */
public interface SocialProviderCatalog {

    List<SocialProvider> enabledProviders();

    default boolean isEnabled(SocialProvider provider) {
        return enabledProviders().contains(provider);
    }
}
