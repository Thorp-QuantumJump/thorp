"""테스트가 읽는 픽스처 경로.

``conftest`` 와 ``golden_inputs`` 가 함께 쓴다. 환경 간 비교 스크립트가 pytest 없는 운영 이미지
안에서 ``golden_inputs`` 를 import 하므로 이 모듈은 pytest 를 import 하지 않는다(spec 004 T030).
conftest 는 fixture 를 두는 자리라 언제든 pytest 를 import 할 수 있어서 경로를 여기로 뺐다.
"""

from pathlib import Path

# 백엔드 테스트와 같은 파일을 읽는다 (테스트 리소스로 복사하지 않는다 — 복사하면 어긋난다).
FIXTURE_DIR = (
    Path(__file__).resolve().parents[2] / "docs" / "specs" / "backtest-messaging" / "examples"
)

#: 3종목 24개월 합성 픽스처(spec 004 T004). 결과는 항상 픽스처로 식별된다.
THREE_ETF_DIR = Path(__file__).resolve().parents[1] / "examples" / "three_etf_24m"
