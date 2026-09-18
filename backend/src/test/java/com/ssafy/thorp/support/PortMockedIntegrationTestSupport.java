package com.ssafy.thorp.support;

import com.ssafy.thorp.backtest.application.port.BacktestControlPublisher;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 브로커로 나가는 두 포트를 목으로 바꾼 {@link IntegrationTestSupport}. 상속한 테스트는 컨텍스트 하나를 함께 쓴다.
 *
 * <p>발행이 성공한 뒤의 기록이나 CANCEL 발행 여부를 보는 테스트가 쓴다. 목은 테스트마다 초기화되므로 상속한 테스트는 {@code @Autowired}로 받아 필요한
 * 동작만 정한다.
 *
 * <p><b>목은 타입 단위로 여기에만 선언한다.</b> 필드에 붙인 {@code @MockitoBean}은 필드 이름까지 컨텍스트 캐시 키에 들어가서, 같은 포트를
 * {@code publisher}와 {@code jobPublisher}로 부르는 두 테스트가 컨텍스트를 따로 만든다.
 */
@MockitoBean(types = {BacktestJobPublisher.class, BacktestControlPublisher.class})
public abstract class PortMockedIntegrationTestSupport extends IntegrationTestSupport {}
