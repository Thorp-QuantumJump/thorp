package com.ssafy.thorp.support;

import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 자동 탐색의 후보 실행기까지 목으로 바꾼 {@link PortMockedIntegrationTestSupport}. 상속한 테스트는 컨텍스트 하나를 함께 쓴다.
 *
 * <p>후보가 실제 실행을 만들지 않고 테스트가 정한 실행 번호를 돌려받아야 하는 자동 탐색 테스트가 쓴다. 실제 실행기가 백테스트 접수를 거치는지 보는 {@code
 * ParamSearchLaunchIT}는 이 기반을 쓰면 안 된다.
 */
@MockitoBean(types = CandidateJobLauncher.class)
public abstract class LauncherMockedIntegrationTestSupport
        extends PortMockedIntegrationTestSupport {

    /** 발행 창을 1로 좁히는 속성. 이 값을 쓰는 검사끼리 컨텍스트를 함께 쓰려면 문자열이 글자 그대로 같아야 하므로 각자 적지 말고 이 상수를 가리킨다. */
    public static final String NARROW_DISPATCH_WINDOW = "thorp.param-search.dispatch-window=1";

    /**
     * 발행 창을 4로 넓히는 속성. 운영 설정은 서버의 벌크 워커 1개에 맞춰 1이지만, <b>창에 둘 이상이 들어가야만 성립하는 검사</b>(동시 수신, 창만큼만
     * 발행하는지)가 있다. 그 검사들이 운영 값 변화에 끌려다니지 않도록 자기 창 크기를 직접 정한다. 위와 같은 이유로 각자 적지 말고 이 상수를 가리킨다.
     */
    public static final String WIDE_DISPATCH_WINDOW = "thorp.param-search.dispatch-window=4";
}
