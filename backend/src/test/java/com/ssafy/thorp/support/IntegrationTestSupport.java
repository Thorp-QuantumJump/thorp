package com.ssafy.thorp.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 애플리케이션 전체를 띄우는 테스트의 공통 기반. <b>이 클래스를 상속한 테스트는 Spring 컨텍스트 하나를 함께 쓴다.</b>
 *
 * <p>Spring은 설정이 글자 그대로 같은 테스트끼리만 컨텍스트를 재사용한다. 속성의 순서, {@code webEnvironment}, {@code
 * AutoConfigureMockMvc}의 유무, {@code @MockitoBean}의 대상과 필드 이름 가운데 하나라도 다르면 컨텍스트를 새로 만든다. 그래서 설정은
 * 여기에만 두고, 상속한 테스트는 {@code @SpringBootTest}·{@code @AutoConfigureMockMvc}·{@code @MockitoBean}을 따로
 * 붙이지 않는다.
 *
 * <p>목이 필요하면 {@link PortMockedIntegrationTestSupport} 또는 {@link
 * LauncherMockedIntegrationTestSupport}를 상속한다. 테스트 하나를 위해 속성을 바꿔야 한다면 먼저 테스트가 그 값 없이 확인할 방법이 없는지
 * 본다. 정말 필요하면 {@code TestPropertySource}나 필드 {@code @MockitoBean}을 붙이되, 그 테스트가 자기 컨텍스트를 따로 만든다는 사실을
 * 주석에 남긴다.
 *
 * <p>속성마다 이유가 있다.
 *
 * <ul>
 *   <li><b>RabbitMQ를 닿지 않는 포트(1)로 돌린다.</b> 발행은 1초 만에 실패하고 결과 리스너는 시작하지 않는다. 브로커가 떠 있는 개발 PC와 없는
 *       CI에서 같은 결과가 나와야 한다. 발행이 성공해야 하는 테스트는 목을 쓰는 기반을 상속한다.
 *   <li><b>재발행·정체 감시·공개 결과 만료 스케줄러를 끈다.</b> 캐시에 남은 컨텍스트는 닫히지 않으므로, 켜 두면 다른 테스트가 공용 DB에 넣은 행을
 *       백그라운드에서 바꾼다. 세 작업의 동작은 빈을 직접 만들거나 부르는 테스트가 본다.
 *   <li><b>대기 실행 한도를 넉넉하게 둔다.</b> 공용 DB에는 앞선 테스트가 남긴 대기 실행이 쌓이므로 기본값(사용자 5, 전체 50)이면 실행 순서에 따라 429가
 *       난다. 한도 동작은 한도를 좁힌 {@code ExecutionLimitApiIT}·{@code ExecutionLimitFullQueueIT}가 따로 본다.
 *   <li>운영자 토큰과 메일 계정은 운영에서만 주입되는 값이라 더미를 넣는다.
 * </ul>
 *
 * <p>{@code webEnvironment}가 {@code RANDOM_PORT}인 까닭은 {@code TestRestTemplate}으로 실제 HTTP 응답을 보는
 * 테스트가 있어서다. {@code MockMvc}는 두 환경에서 똑같이 동작한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.backtest.liveness.enabled=false",
            "thorp.result.expiry-sweep.enabled=false",
            "thorp.backtest.limits.max-queued-per-user=1000",
            "thorp.backtest.limits.max-queued-total=1000",
            "thorp.backtest.limits.max-queued-anonymous=1000",
            // 기본값과 같은 값이지만 적어 둔다. 자동 탐색 빈은 이 플래그가 켜져 있어야 만들어지므로,
            // 기본값이 바뀌면 여러 테스트가 "빈이 없다"로 깨지고 원인이 코드 어디에도 남지 않는다.
            "thorp.param-search.enabled=true",
            "thorp.ops.token=" + IntegrationTestSupport.OPS_TOKEN,
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport extends PostgresTestSupport {

    /** 운영자 경로({@code /internal/**})를 여는 토큰. 요청 헤더에 실을 값과 설정값이 갈라지지 않게 여기 하나만 둔다. */
    public static final String OPS_TOKEN = "it-ops-token";
}
