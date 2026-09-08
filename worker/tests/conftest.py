from pathlib import Path

# 백엔드 테스트와 같은 파일을 읽는다 (테스트 리소스로 복사하지 않는다 — 복사하면 어긋난다).
FIXTURE_DIR = (
    Path(__file__).resolve().parents[2] / "docs" / "specs" / "backtest-messaging" / "examples"
)
