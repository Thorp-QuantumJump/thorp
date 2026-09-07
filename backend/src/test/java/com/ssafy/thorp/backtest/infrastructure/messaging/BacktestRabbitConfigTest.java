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
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

/** 토폴로지 선언이 README §2 표와 일치하는지. 컨텍스트·브로커 없이 검사한다. */
class BacktestRabbitConfigTest {

    private final Declarables topology = new BacktestRabbitConfig().backtestTopology();

    @Test
    void exchange_2_queue_4_binding_4() {
        assertThat(topology.getDeclarablesByType(Exchange.class)).hasSize(2);
        assertThat(topology.getDeclarablesByType(Queue.class)).hasSize(4);
        assertThat(topology.getDeclarablesByType(Binding.class)).hasSize(4);
    }

    @Test
    void 작업_이벤트_큐는_DLX_인자를_가진다() {
        Queue jobs = queue(BacktestRabbitConfig.JOBS_QUEUE);
        assertThat(jobs.isDurable()).isTrue();
        assertThat(jobs.getArguments())
                .containsEntry("x-dead-letter-exchange", BacktestRabbitConfig.DEAD_LETTER_EXCHANGE)
                .containsEntry(
                        "x-dead-letter-routing-key",
                        BacktestRabbitConfig.JOBS_DEAD_LETTER_ROUTING_KEY);

        Queue events = queue(BacktestRabbitConfig.EVENTS_QUEUE);
        assertThat(events.getArguments())
                .containsEntry("x-dead-letter-exchange", BacktestRabbitConfig.DEAD_LETTER_EXCHANGE)
                .containsEntry(
                        "x-dead-letter-routing-key",
                        BacktestRabbitConfig.EVENTS_DEAD_LETTER_ROUTING_KEY);
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
