"""사례 연결 계약 상수 대조.

큐·exchange·routing key·스키마 버전은 백엔드와 합의한 계약값이다. 백테스트는 워커
테스트가 백엔드 ``BacktestRabbitConfig.java``를 직접 읽어 대조하는데, 011은 그 Java
파일(``RegimeLinkRabbitConfig``, T030)이 아직 없으므로 **계약 문서**
(``docs/specs/regime-link-messaging/README.md``)와 대조한다. Java 파일이 생기면
아래 ``test_topology_constants_match_the_backend``가 저절로 살아난다.
"""

import re
from pathlib import Path

import pytest
from fixture_paths import REGIME_LINK_BACKEND_CONFIG, REGIME_LINK_CONTRACT_README

from worker.regime_link import messaging

#: 계약 문서에서 이름을 읽어 올 절. 다른 절의 예시 문장이 섞이지 않게 범위를 좁힌다.
TOPOLOGY_SECTION = "## 메시지 속성과 토폴로지"

#: 모듈이 들고 있는 계약 이름 상수. 값이 아니라 **집합**을 대조해 한쪽에만 있는
#: 이름을 잡는다.
NAME_CONSTANTS = (
    "EXCHANGE",
    "DEAD_LETTER_EXCHANGE",
    "JOBS_QUEUE",
    "JOBS_ROUTING_KEY",
    "JOBS_DEAD_LETTER_QUEUE",
    "EVENTS_QUEUE",
    "EVENTS_ROUTING_KEY",
)


def contract_section() -> str:
    text = REGIME_LINK_CONTRACT_README.read_text(encoding="utf-8")
    start = text.index(TOPOLOGY_SECTION)
    end = text.find("\n## ", start + 1)
    if end == -1:
        end = len(text)
    return text[start:end]


# --- 계약 상수 -----------------------------------------------------------------------


def test_topology_names_match_the_contract() -> None:
    """워커가 아는 이름과 계약 문서가 적은 이름이 정확히 같다 (T030 전까지)."""
    documented = set(re.findall(r"`(regime-link(?:\.[a-z.]+)?)`", contract_section()))
    assert documented, "계약 문서에서 토폴로지 이름을 찾지 못했다"

    held = {getattr(messaging, name) for name in NAME_CONSTANTS}
    assert held == documented


def test_schema_version_matches_the_contract() -> None:
    match = re.search(r"schemaVersion: (\d+)", contract_section())
    assert match is not None, "계약 문서에서 schemaVersion 을 찾지 못했다"
    assert messaging.SCHEMA_VERSION == int(match.group(1)) == 1


def test_delivery_limit_matches_the_contract() -> None:
    match = re.search(r"x-delivery-limit=(\d+)", contract_section())
    assert match is not None, "계약 문서에서 x-delivery-limit 을 찾지 못했다"
    assert messaging.JOBS_DELIVERY_LIMIT == int(match.group(1)) == 1


def test_prefetch_is_one() -> None:
    assert messaging.PREFETCH_COUNT == 1


def test_topology_constants_match_the_backend() -> None:
    """백엔드 ``RegimeLinkRabbitConfig`` 가 생기면 그 상수와 직접 대조한다."""
    if not REGIME_LINK_BACKEND_CONFIG.exists():
        pytest.skip("RegimeLinkRabbitConfig.java 는 spec 011 T030(묶음 M5)에서 만든다")

    source = REGIME_LINK_BACKEND_CONFIG.read_text(encoding="utf-8")
    declared = dict(re.findall(r'public static final String (\w+) = "([^"]+)";', source))
    assert declared, "backend RegimeLinkRabbitConfig constants not found"
    for name, value in declared.items():
        # 백엔드에만 있는 이름은 계약 문서와 이 모듈에도 더해야 한다
        assert hasattr(messaging, name), f"{name} 이 워커 계약 상수에 없다"
        assert getattr(messaging, name) == value, name

    version = re.search(r"public static final int SCHEMA_VERSION = (\d+);", source)
    assert version is not None, "backend SCHEMA_VERSION constant not found"
    assert messaging.SCHEMA_VERSION == int(version.group(1))


# --- 경계 --------------------------------------------------------------------------


def test_messaging_has_no_broker_client() -> None:
    """pika 는 ``rabbitmq`` 에만 있다.

    이 경계 덕분에 브로커 없이 소비 흐름을 시험할 수 있다.
    """
    assert messaging.__file__ is not None, "messaging 모듈 경로를 찾지 못했다"
    source = Path(messaging.__file__).read_text(encoding="utf-8")
    pattern = r"^\s*(?:from|import)\s+([\w.]+)"
    imported = set(re.findall(pattern, source, flags=re.MULTILINE))

    assert imported, "import 문을 찾지 못했다"
    assert not {name for name in imported if name.split(".")[0] == "pika"}
