"""워커 설정. 환경변수(.env)에서 읽는다.

접속 정보는 환경변수로만 주입한다(코드에 하드코딩 금지). 큐·exchange·routing key는 설정이
아니라 백엔드와 합의한 **계약 상수**이므로 여기가 아니라 ``worker.messaging`` 에 둔다.
"""

from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

#: 워커 역할(spec 004 R-20). ``single`` 은 단건 큐만 push 로 소비하고, ``flex`` 는 벌크 큐를 먼저
#: 확인한 뒤 비어 있으면 단건 큐를 pull 로 집는다. 큐 이름은 계약 상수(``worker.messaging``)다.
WorkerRole = Literal["single", "flex"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # compose 가 WORKER_ROLE 로 주입한다. 기본은 단건 전용 — 값이 없으면 큐 분리 전과 같이 동작한다.
    worker_role: WorkerRole = "single"

    # docker-compose.dev.yml 이 주입하는 이름·형태를 그대로 받는다(단일 AMQP URL).
    # host/port/user/password를 따로 받지 않는다.
    rabbitmq_url: str = "amqp://thorp:thorp@localhost:5672/"

    # 001이 적재한 시장 자료 DB(읽기 전용). compose 가 MARKET_DATA_URL 로 주입한다.
    # 기본값을 두지 않는다 — 값이 없으면 조용히 로컬 DB 를 읽는 대신 실패해야 한다.
    market_data_url: str | None = None


settings = Settings()
