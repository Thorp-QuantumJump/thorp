"""워커 엔트리포인트: ``python -m worker.main``.

RabbitMQ ``backtest.jobs`` 를 소비해 백테스트 엔진을 실행하고 ``backtest.events`` 로
STARTED / PROGRESS / FINISHED를 발행한다. 같은 코드를 여러 프로세스/컨테이너로 실행해
수평 확장한다(competing consumers, prefetch 1).

토폴로지 선언은 백엔드 책임이므로(계약 README §2) 큐가 없으면 만들지 않고 종료한다.
접속 정보는 환경변수 ``RABBITMQ_URL``(브로커)·``MARKET_DATA_URL``(시장 자료 DB, 읽기 전용)로만
주입한다.
"""

import logging

from pika.exceptions import AMQPError

from worker.config import settings
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import PostgresMarketDataProvider
from worker.rabbitmq import QueueMissingError, run_consumer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
# pika는 연결·채널 단계마다 INFO 로그를 남겨 워커 로그를 덮는다. 경고 이상만 본다.
logging.getLogger("pika").setLevel(logging.WARNING)
logger = logging.getLogger("worker")


def main() -> int:
    if not settings.market_data_url:
        # 기본값을 두지 않는다 — 조용히 다른 DB 를 읽는 대신 여기서 멈춘다(R-09, 원칙 X).
        logger.error("MARKET_DATA_URL is not set; the worker reads market data only from 001's DB")
        return 1
    # 엔진 인스턴스는 여러 실행에서 재사용한다. 생성자는 접속하지 않는다(실행마다 읽기 전용 접속).
    engine = PortfolioBacktestEngine(PostgresMarketDataProvider(settings.market_data_url))
    try:
        run_consumer(settings.rabbitmq_url, engine)
    except QueueMissingError as exc:
        logger.error("%s", exc)
        return 1
    except AMQPError as exc:
        # 접속 정보에 비밀번호가 들어 있으므로 URL은 로그에 남기지 않는다.
        logger.error("RabbitMQ connection failed (check RABBITMQ_URL): %s", exc)
        return 1
    except KeyboardInterrupt:
        logger.info("worker stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
