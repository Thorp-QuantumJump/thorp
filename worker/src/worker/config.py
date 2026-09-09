"""워커 설정. 환경변수(.env)에서 읽는다.

접속 정보는 환경변수로만 주입한다(코드에 하드코딩 금지). 큐·exchange·routing key는 설정이
아니라 백엔드와 합의한 **계약 상수**이므로 여기가 아니라 ``worker.messaging`` 에 둔다.
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # docker-compose.dev.yml 이 주입하는 이름·형태를 그대로 받는다(단일 AMQP URL).
    # host/port/user/password를 따로 받지 않는다.
    rabbitmq_url: str = "amqp://thorp:thorp@localhost:5672/"


settings = Settings()
