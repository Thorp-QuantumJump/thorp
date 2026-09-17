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
        extends PortMockedIntegrationTestSupport {}
