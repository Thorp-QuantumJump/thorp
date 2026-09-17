package com.ssafy.thorp.support;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RabbitMQ에 붙는 테스트의 공통 기반. 브로커 컨테이너를 테스트 JVM에서 한 번만 띄우고 모든 클래스가 함께 쓴다.
 *
 * <p><b>컨테이너를 {@code @Container}로 관리하지 않고 싱글턴으로 둔다.</b> 이유는 {@link PostgresTestSupport}와 같다. 클래스마다
 * 띄우면 기동에만 클래스당 십수 초가 들고, 클래스가 끝나 멈춘 컨테이너의 포트로 캐시된 컨텍스트의 리스너가 계속 재접속한다. 시작을 {@code @BeforeAll}에서 하는
 * 이유도 같다. 정리는 Testcontainers의 Ryuk가 JVM 종료 시 한다.
 *
 * <p>브로커를 함께 쓰므로 두 가지를 지킨다.
 *
 * <ul>
 *   <li><b>컨텍스트를 클래스 끝에 닫는다({@link DirtiesContext}).</b> 닫지 않으면 캐시된 컨텍스트의 리스너가 살아 있는 브로커에 그대로 붙어
 *       있다가, 뒤에 오는 클래스의 리스너와 같은 큐를 나눠 소비한다. 예를 들어 {@code BacktestEventListenerIT}의 컨텍스트가 남아 있으면
 *       {@code BacktestDeadLetterListenerIT}가 DLQ로 보낸 메시지를 그쪽 리스너가 가져갈 수 있다. 이 컨텍스트들은 클래스마다 구성이 달라
 *       원래 다른 클래스가 재사용하지 않으므로, 닫아도 잃는 것이 없다.
 *   <li><b>읽는 큐는 검사 전에 비운다.</b> 앞 클래스가 남긴 메시지가 섞이지 않게 하기 위해서다. 큐마다 쓰는 테스트가 다르므로 각 테스트가
 *       {@code @BeforeEach}에서 비운다.
 * </ul>
 *
 * <p>다른 기반 클래스를 이미 상속한 테스트는 이 클래스를 상속하지 않고 {@link #RABBITMQ}를 자기 {@code @ServiceConnection} 필드에 담아
 * 쓴다. 그때는 {@link #startContainerOnce()}를 자기 {@code @BeforeAll}에서 부르고 {@code @DirtiesContext}도 직접
 * 붙인다. 이미지 태그는 운영 Compose와 맞춘다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
public abstract class RabbitTestSupport {

    @ServiceConnection
    public static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @BeforeAll
    public static void startContainerOnce() {
        if (!RABBITMQ.isRunning()) {
            RABBITMQ.start();
        }
    }
}
