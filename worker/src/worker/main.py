"""워커 엔트리포인트.

Kafka Consumer Group으로 묶인 워커들이 Job을 나눠 받아 처리한다.
같은 코드를 여러 프로세스/컨테이너로 실행해 수평 확장한다.
"""

import json
import logging

from confluent_kafka import Consumer

from worker.config import settings
from worker.models import BacktestJob

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("worker")


def main() -> None:
    consumer = Consumer(
        {
            "bootstrap.servers": settings.kafka_bootstrap_servers,
            "group.id": settings.consumer_group_id,
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
    )
    consumer.subscribe([settings.backtest_job_topic])
    logger.info(
        "worker started. topic=%s group=%s",
        settings.backtest_job_topic,
        settings.consumer_group_id,
    )

    try:
        while True:
            msg = consumer.poll(1.0)
            if msg is None:
                continue
            if msg.error():
                logger.error("kafka error: %s", msg.error())
                continue

            job = BacktestJob.model_validate(json.loads(msg.value()))
            logger.info("received job %s (strategy=%s)", job.job_id, job.strategy)

            # TODO: 상태 RUNNING 업데이트 -> engine.run_backtest -> 결과 저장 -> COMPLETED
            consumer.commit(msg)
    finally:
        consumer.close()


if __name__ == "__main__":
    main()
