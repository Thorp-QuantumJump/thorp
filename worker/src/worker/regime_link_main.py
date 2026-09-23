"""사례 연결 워커 엔트리포인트: ``python -m worker.regime_link_main``.

``regime-link.jobs`` 를 소비해 사후 구간을 계산하고 ``regime-link.events`` 로 STARTED / FINISHED 를
발행한다. 백테스트 워커(``python -m worker.main``)와 **다른 프로세스**다 — 큐도 스키마도 다르고,
한쪽이 긴 백테스트로 막혀 있는 동안 다른 쪽이 멈추지 않아야 한다. 같은 코드를 여러 개 실행해
수평 확장하는 것(competing consumers, 한 번에 한 건)은 같다.

토폴로지 선언은 백엔드 책임이므로 큐가 없으면 만들지 않고 종료한다. 접속 정보는 환경변수
``RABBITMQ_URL``(브로커)·``MARKET_DATA_URL``(시장 자료 DB, 읽기 전용)로만 주입한다.
"""

import logging

from pika.exceptions import AMQPError

from worker.config import settings
from worker.regime_link.market_data import PostgresClosePriceLoader
from worker.regime_link.rabbitmq import QueueMissingError, run_consumer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
# pika 는 연결·채널 단계마다 INFO 로그를 남겨 워커 로그를 덮는다. 경고 이상만 본다.
logging.getLogger("pika").setLevel(logging.WARNING)
logger = logging.getLogger("worker.regime_link")


def main() -> int:
    if not settings.market_data_url:
        # 기본값을 두지 않는다 — 조용히 다른 DB 를 읽는 대신 여기서 멈춘다(원칙 X).
        logger.error("MARKET_DATA_URL is not set; the worker reads market data only from 001's DB")
        return 1
    if not settings.rabbitmq_url:
        logger.error("RABBITMQ_URL is not set; the worker has no broker to consume jobs from")
        return 1
    # 적재기는 여러 작업에서 재사용한다. 생성자는 접속하지 않는다(작업마다 읽기 전용 접속).
    prices = PostgresClosePriceLoader(settings.market_data_url)
    try:
        run_consumer(settings.rabbitmq_url, prices)
    except QueueMissingError as exc:
        logger.error("%s", exc)
        return 1
    except AMQPError as exc:
        # 접속 정보에 비밀번호가 들어 있으므로 URL 은 로그에 남기지 않는다.
        logger.error("RabbitMQ connection failed (check RABBITMQ_URL): %s", exc)
        return 1
    except KeyboardInterrupt:
        logger.info("regime link worker stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
