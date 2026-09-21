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

#: 계약 이름의 접두어. exchange·큐·routing key 가 모두 이것으로 시작한다.
NAME_PREFIX = "regime-link"


def contract_section() -> str:
    text = REGIME_LINK_CONTRACT_README.read_text(encoding="utf-8")
    start = text.index(TOPOLOGY_SECTION)
    end = text.find("\n## ", start + 1)
    if end == -1:
        end = len(text)
    return text[start:end]


def documented_names() -> set[str]:
    """계약 문서의 토폴로지 절이 backtick 으로 적은 이름.

    글자 종류를 제한하는 정규식으로 뽑지 않는다. ``regime-link.jobs-dlq`` 처럼
    하이픈·숫자가 든 이름을 계약에 새로 적으면 그런 정규식은 그 이름을 **조용히
    건너뛰어**, 워커도 모르는 이름이 계약에만 남은 상태가 통과한다. backtick 토큰을
    통째로 걷은 뒤 접두어로만 거른다.
    """
    tokens = re.findall(r"`([^`]+)`", contract_section())
    return {token for token in tokens if token.startswith(NAME_PREFIX)}


def held_names() -> set[str]:
    """모듈이 들고 있는 계약 이름 상수.

    대조할 상수를 손으로 적은 목록으로 두지 않는다 — 상수를 더하면서 목록에 넣는
    것을 잊으면 그 상수는 계약과 한 번도 대조되지 않은 채 통과한다.
    """
    return {
        value
        for name, value in vars(messaging).items()
        if not name.startswith("_") and isinstance(value, str) and value.startswith(NAME_PREFIX)
    }


# --- 계약 상수 -----------------------------------------------------------------------


def test_topology_names_match_the_contract() -> None:
    """워커가 아는 이름과 계약 문서가 적은 이름이 정확히 같다 (T030 전까지)."""
    documented = documented_names()
    assert documented, "계약 문서에서 토폴로지 이름을 찾지 못했다"

    assert held_names() == documented


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
        # 경로가 틀려 영영 skip 되는 것과 아직 T030 을 안 한 것을 가른다. 이 skip 은
        # "못 잡는 쪽"으로 실패하므로 닻이 없으면 대조가 영영 돌지 않아도 알 수 없다.
        assert REGIME_LINK_BACKEND_CONFIG.parent.is_dir(), (
            f"{REGIME_LINK_BACKEND_CONFIG.parent} 가 없다 — 경로가 틀렸거나 패키지가 옮겨졌다"
        )
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
