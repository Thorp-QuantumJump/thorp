package com.ssafy.thorp.strategy.infrastructure.registry;

import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

/**
 * 합성 등록표로 <b>실제 로더</b>를 만들어 주는 테스트 도우미.
 *
 * <p>003 조회 API의 검증은 포트를 흉내 낸 가짜가 아니라 {@link FileParameterRegistry}로 한다. 조회 응답에는 유효성 규칙의 조건이 그대로
 * 실리는데({@code left}·{@code operator}·{@code right}), 007 쪽 {@code RegistryFixtures}는 규칙을 {@code
 * BiPredicate}로 들고 있어 조건을 밖으로 내보낼 형태가 아니다. 가짜로 검증하면 그 필드가 빈 채로 통과한다.
 *
 * <p>이 클래스가 {@code infrastructure.registry} 패키지에 있는 이유는 {@link FileParameterRegistry}의 {@code
 * Resource} 생성자가 package-private이기 때문이다. 운영 코드의 접근 범위를 넓히지 않으려고 도우미를 같은 패키지에 둔다.
 */
public final class SyntheticRegistry {

    /** 계약 픽스처와 같은 내용의 합성 등록표. {@code docs/specs/strategy-registry-api/examples/}와 짝이다. */
    public static final String CONTRACT_RESOURCE = "registry/contract-registry.yml";

    private SyntheticRegistry() {}

    /** 계약 픽스처와 같은 내용의 등록표. */
    public static ParameterRegistryPort contract() {
        return new FileParameterRegistry(new ClassPathResource(CONTRACT_RESOURCE));
    }

    /** 본문을 그 자리에 적은 등록표. 검증하려는 조건만 담은 작은 등록표를 만들 때 쓴다. */
    public static ParameterRegistryPort of(String yaml) {
        return new FileParameterRegistry(
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
