"""워커 설정. 환경변수(.env)에서 읽는다."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    kafka_bootstrap_servers: str = "localhost:9092"
    backtest_job_topic: str = "backtest.jobs"
    consumer_group_id: str = "backtest-workers"
    database_url: str = "postgresql://quantum:quantum@localhost:5432/quantumjump"


settings = Settings()
