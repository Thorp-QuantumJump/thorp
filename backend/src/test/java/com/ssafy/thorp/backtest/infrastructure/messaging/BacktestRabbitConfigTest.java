package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

/** 토폴로지 선언이 README §2 표와 일치하는지. 컨텍스트·브로커 없이 검사한다. */
class BacktestRabbitConfigTest {

    private final Declarables topology = new BacktestRabbitConfig().backtestTopology();

    @Test
    void exchange_3_queue_5_binding_5() {
        assertThat(topology.getDeclarablesByType(Exchange.class)).hasSize(3);
        assertThat(topology.getDeclarablesByType(Queue.class)).hasSize(5);
        assertThat(topology.getDeclarablesByType(Binding.class)).hasSize(5);
    }

    @Test
    void 벌크_작업_큐는_단건_큐와_속성이_같고_같은_DLQ로_간다() {
        // 어느 큐에서 죽어도 T010 의 DLQ 집계 하나를 타야 한다 — 재전달 상한과 DLX 가 다르면 큐마다 종결 규칙이 갈린다.
        Queue bulk = queue(BacktestRabbitConfig.BULK_JOBS_QUEUE);
        assertThat(bulk.isDurable()).isTrue();
        assertThat(bulk.getArguments())
                .isEqualTo(queue(BacktestRabbitConfig.JOBS_QUEUE).getArguments());
    }

    @Test
    void 제어_exchange는_durable_fanout이다() {
        Exchange control =
                topology.getDeclarablesByType(Exchange.class).stream()
                        .filter(e -> e.getName().equals(BacktestRabbitConfig.CONTROL_EXCHANGE))
                        .findFirst()
                        .orElseThrow();
        assertThat(control).isInstanceOf(FanoutExchange.class);
        assertThat(control.isDurable()).isTrue();
        // 워커마다 exclusive·auto-delete 큐를 바인딩하므로 백엔드는 큐·binding을 선언하지 않는다
    }

    @Test
    void 작업_큐는_quorum이고_재전달_1회_뒤_DLQ로_간다() {
        Queue jobs = queue(BacktestRabbitConfig.JOBS_QUEUE);
        assertThat(jobs.isDurable()).isTrue();
        assertThat(jobs.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-delivery-limit", BacktestRabbitConfig.JOBS_DELIVERY_LIMIT)
                .containsEntry("x-dead-letter-exchange", BacktestRabbitConfig.DEAD_LETTER_EXCHANGE)
                .containsEntry(
                        "x-dead-letter-routing-key",
                        BacktestRabbitConfig.JOBS_DEAD_LETTER_ROUTING_KEY);
    }

    @Test
    void 이벤트_큐는_DLX_인자를_가진다() {
        Queue events = queue(BacktestRabbitConfig.EVENTS_QUEUE);
        assertThat(events.getArguments())
                .containsEntry("x-dead-letter-exchange", BacktestRabbitConfig.DEAD_LETTER_EXCHANGE)
                .containsEntry(
                        "x-dead-letter-routing-key",
                        BacktestRabbitConfig.EVENTS_DEAD_LETTER_ROUTING_KEY)
                .doesNotContainKey("x-queue-type");
    }

    @Test
    void binding은_설계_routing_key로_연결된다() {
        assertThat(topology.getDeclarablesByType(Binding.class))
                .extracting(Binding::getExchange, Binding::getDestination, Binding::getRoutingKey)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                BacktestRabbitConfig.EXCHANGE,
                                BacktestRabbitConfig.JOBS_QUEUE,
                                BacktestRabbitConfig.JOBS_ROUTING_KEY),
                        org.assertj.core.groups.Tuple.tuple(
                                BacktestRabbitConfig.EXCHANGE,
                                BacktestRabbitConfig.BULK_JOBS_QUEUE,
                                BacktestRabbitConfig.BULK_JOBS_ROUTING_KEY),
                        org.assertj.core.groups.Tuple.tuple(
                                BacktestRabbitConfig.EXCHANGE,
                                BacktestRabbitConfig.EVENTS_QUEUE,
                                BacktestRabbitConfig.EVENTS_ROUTING_KEY),
                        org.assertj.core.groups.Tuple.tuple(
                                BacktestRabbitConfig.DEAD_LETTER_EXCHANGE,
                                BacktestRabbitConfig.JOBS_DEAD_LETTER_QUEUE,
                                BacktestRabbitConfig.JOBS_DEAD_LETTER_ROUTING_KEY),
                        org.assertj.core.groups.Tuple.tuple(
                                BacktestRabbitConfig.DEAD_LETTER_EXCHANGE,
                                BacktestRabbitConfig.EVENTS_DEAD_LETTER_QUEUE,
                                BacktestRabbitConfig.EVENTS_DEAD_LETTER_ROUTING_KEY));
    }

    @Test
    void 브로커에_닿지_못해도_기동_러너는_예외를_던지지_않는다() throws Exception {
        ConnectionFactory down = mock(ConnectionFactory.class);
        when(down.createConnection())
                .thenThrow(new AmqpConnectException(new IOException("refused")));

        assertThatCode(() -> new BacktestRabbitConfig().declareBacktestTopology(down).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void 브로커가_있으면_기동_시_연결을_한_번_연다() throws Exception {
        ConnectionFactory up = mock(ConnectionFactory.class);

        new BacktestRabbitConfig().declareBacktestTopology(up).run(null);

        verify(up).createConnection();
    }

    private Queue queue(String name) {
        return topology.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
