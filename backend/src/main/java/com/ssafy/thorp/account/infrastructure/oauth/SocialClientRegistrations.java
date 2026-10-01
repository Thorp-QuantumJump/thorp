package com.ssafy.thorp.account.infrastructure.oauth;

import com.ssafy.thorp.account.application.port.SocialProviderCatalog;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * 자격 증명이 설정된 제공자만 담은 등록 저장소.
 *
 * <p>등록이 하나도 없어도 비어 있는 채로 동작한다. Spring의 {@code InMemoryClientRegistrationRepository}는 빈 목록을 받지 않아서,
 * 자격 증명 없는 환경에서도 {@code oauth2Login()}을 붙이려면 이 저장소가 필요하다.
 */
@Component
public class SocialClientRegistrations
        implements ClientRegistrationRepository,
                Iterable<ClientRegistration>,
                SocialProviderCatalog {

    private final Map<SocialProvider, ClientRegistration> registrations =
            new EnumMap<>(SocialProvider.class);
    private final Map<SocialProvider, SocialProviderAdapter> adapters =
            new EnumMap<>(SocialProvider.class);

    public SocialClientRegistrations(
            SocialAuthProperties properties, List<SocialProviderAdapter> adapterBeans) {
        for (SocialProviderAdapter adapter : adapterBeans) {
            SocialAuthProperties.Client client = clientOf(properties, adapter.provider());
            if (!client.configured()) {
                continue;
            }
            adapters.put(adapter.provider(), adapter);
            registrations.put(
                    adapter.provider(),
                    adapter.registration()
                            .clientId(client.clientId())
                            .clientSecret(client.clientSecret())
                            .redirectUri(properties.redirectUriTemplate())
                            .build());
        }
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        return SocialProvider.fromRegistrationId(registrationId)
                .map(registrations::get)
                .orElse(null);
    }

    public Optional<SocialProviderAdapter> adapter(String registrationId) {
        return SocialProvider.fromRegistrationId(registrationId).map(adapters::get);
    }

    @Override
    public List<SocialProvider> enabledProviders() {
        return Collections.unmodifiableList(new ArrayList<>(registrations.keySet()));
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        return Collections.unmodifiableCollection(registrations.values()).iterator();
    }

    private static SocialAuthProperties.Client clientOf(
            SocialAuthProperties properties, SocialProvider provider) {
        return switch (provider) {
            case KAKAO -> properties.kakao();
            case GOOGLE -> properties.google();
            case NAVER -> properties.naver();
        };
    }
}
