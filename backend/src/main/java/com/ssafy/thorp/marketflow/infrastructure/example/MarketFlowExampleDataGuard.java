package com.ssafy.thorp.marketflow.infrastructure.example;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 운영에서 예시 자료로 지표를 그리려 하면 <b>기동을 막는다</b> (FR-022).
 *
 * <p>{@link com.ssafy.thorp.regime.infrastructure.config.RegimeFixtureGuard} 와 같은 가드를 시장 흐름 쪽에 둔
 * 것이다. 010 의 임시 대역은 이미 막혀 있었는데 009 가 읽는 예시 어댑터는 프로필 제한이 없어 운영에서도 그대로 올라왔다.
 *
 * <p><b>왜 기동 실패인가.</b> 요청마다 막으면 운영이 일단 뜨고 화면은 지어낸 값을 그린다. 값이 그럴듯해서 아무도 이상하게 여기지 않고, 그 상태가 얼마나
 * 오래갔는지도 나중에 알 수 없다. 뜨지 않는 편이 훨씬 시끄럽고 그래서 고쳐진다.
 *
 * <p><b>끄는 길을 두지 않는다.</b> 속성으로 켜고 끌 수 있게 하면 배포가 급할 때 그 속성이 켜지고, 켜진 채로 잊힌다. 운영에서 예시 자료를 쓸 이유는 없으므로
 * 선택지를 만들지 않는다.
 *
 * <p>이 가드는 {@code marketflow} 의 지표 조회가 008 스냅샷을 읽도록 바뀌면(T064) 필요 없어지고, 예시 어댑터와 함께 사라진다(T071).
 */
@Configuration
public class MarketFlowExampleDataGuard {

    private static final String PROD_PROFILE = "prod";

    public MarketFlowExampleDataGuard(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            throw new IllegalStateException(
                    "prod 프로파일에서는 시장 흐름 지표를 예시 자료로 그릴 수 없습니다"
                            + "(FR-022). "
                            + ExampleMacroIndicatorSnapshotAdapter.RESOURCE_PATH
                            + " 는 지어낸 값이라 운영 자료가 될 수 없습니다. "
                            + "운영에서는 marketflow 의 지표 조회를 008 스냅샷으로 바꾼 뒤(T064) 기동합니다.");
        }
    }
}
