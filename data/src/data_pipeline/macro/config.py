"""원천별 접속 설정 — 주소·타임아웃·재시도를 한자리에 모은다 (spec 008 FR-011b·FR-021).

**인증키는 환경변수에서만 읽는다**(FR-021). 코드에 적지 않고, 이 모듈이 돌려주는 설정에도 키를
담지 않는다 — 설정 객체를 로그나 오류 메시지에 그대로 찍는 일이 흔한데 거기 키가 섞여 있으면
저장소가 아니라 로그로 새어 나간다. 키가 필요한 쪽은 :func:`require_api_key` 로 그때 읽는다.

**키가 없어도 임포트는 된다.** 테스트는 ``tests/fixtures/macro/`` 의 저장된 응답만 읽으므로
(FR-022) 키 없는 환경에서 모듈을 불러오는 것만으로 실패하면 안 된다. 없는 키는 값을 꺼낼 때
비로소 문제가 된다.

재시도 값은 **출발값이고 실측 뒤 조정한다**(원칙 IX). 지금 넣은 3회·1·2·4초는 근거 있는 상한이
아니라 "정해진 횟수"를 판정 가능한 수로 바꾼 것이다. 원천이 수치 한도를 밝힌 곳은 그 한도가
우선이며(FR-017·FR-017a), 그것은 등록표가 들고 어댑터가 따른다.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Final

#: 원천 식별자. 등록표의 `primary_source` 와 같은 값을 쓴다.
SOURCE_ECOS: Final[str] = "ecos"
SOURCE_KOSIS: Final[str] = "kosis"
SOURCE_CUSTOMS: Final[str] = "customs"
SOURCE_OECD: Final[str] = "oecd"

SOURCE_IDS: Final[tuple[str, ...]] = (SOURCE_ECOS, SOURCE_KOSIS, SOURCE_CUSTOMS, SOURCE_OECD)

#: 재시도 출발값 (FR-011b). 실측 뒤 조정한다.
DEFAULT_MAX_ATTEMPTS: Final[int] = 3
DEFAULT_BACKOFF_SECONDS: Final[float] = 1.0
DEFAULT_BACKOFF_MULTIPLIER: Final[float] = 2.0
DEFAULT_TIMEOUT_SECONDS: Final[float] = 30.0


class MacroConfigError(ValueError):
    """설정 값이 없거나 쓸 수 없는 형태다."""


@dataclass(frozen=True)
class SourceConfig:
    """원천 하나의 접속 설정. **인증키를 담지 않는다** — 모듈 docstring 참고."""

    source_id: str
    base_url: str
    api_key_env: str | None
    timeout_seconds: float
    max_attempts: int
    backoff_seconds: float
    backoff_multiplier: float

    def backoff_for(self, attempt: int) -> float:
        """``attempt`` 번째 재시도 전에 기다릴 초. 1부터 센다 (1·2·4초)."""
        if attempt < 1:
            raise MacroConfigError(f"재시도 횟수는 1부터다: {attempt}")
        return self.backoff_seconds * (self.backoff_multiplier ** (attempt - 1))


# 기본 주소는 Backend `market` 모듈이 이미 쓰고 있는 값과 같다
# (`MarketApiProperties`). 두 곳이 서로 다른 주소를 들면 같은 원천을 두 얼굴로
# 다루게 되고, 원천이 주소를 바꿀 때 한쪽만 고쳐진다.
_DEFAULTS: Final[dict[str, tuple[str, str | None]]] = {
    SOURCE_ECOS: ("https://ecos.bok.or.kr/api", "ECOS_API_KEY"),
    SOURCE_KOSIS: ("https://kosis.kr/openapi/Param/statisticsParameterData.do", "KOSIS_API_KEY"),
    SOURCE_CUSTOMS: (
        "https://apis.data.go.kr/1220000/Newtrade/getNewtradeList",
        "DATA_GO_KR_SERVICE_KEY",
    ),
    # 경제협력개발기구 SDMX 는 인증키가 필요 없다(pd11 §OECD).
    SOURCE_OECD: ("https://sdmx.oecd.org/public/rest/data", None),
}


#: 인증키가 들어 있는 환경변수 이름 전부 (FR-021).
#:
#: 오류·로그를 내보내기 전에 이 값들을 지운다. 이름을 한자리에 모아 두는 이유는, 원천이
#: 늘 때 지우는 쪽을 함께 고치지 않으면 새 키만 조용히 새기 때문이다.
API_KEY_ENV_NAMES: Final[tuple[str, ...]] = tuple(
    sorted({env for _, env in _DEFAULTS.values() if env})
)


def _env_name(source_id: str, suffix: str) -> str:
    return f"MACRO_{source_id.upper()}_{suffix}"


def _read_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = float(raw)
    except ValueError:
        raise MacroConfigError(f"{name}은(는) 숫자여야 한다: {raw!r}") from None
    if value <= 0:
        raise MacroConfigError(f"{name}은(는) 0보다 커야 한다: {value}")
    return value


def _read_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = int(raw)
    except ValueError:
        raise MacroConfigError(f"{name}은(는) 정수여야 한다: {raw!r}") from None
    if value < 1:
        raise MacroConfigError(f"{name}은(는) 1 이상이어야 한다: {value}")
    return value


def load_source_config(source_id: str) -> SourceConfig:
    """원천 하나의 설정을 환경변수에서 읽는다. 없는 값은 기본값을 쓴다."""
    try:
        default_url, api_key_env = _DEFAULTS[source_id]
    except KeyError:
        raise MacroConfigError(
            f"모르는 원천이다: {source_id!r}. 쓸 수 있는 값은 {', '.join(SOURCE_IDS)}"
        ) from None
    return SourceConfig(
        source_id=source_id,
        base_url=os.environ.get(_env_name(source_id, "BASE_URL"), default_url).rstrip("/"),
        api_key_env=api_key_env,
        timeout_seconds=_read_float(_env_name(source_id, "TIMEOUT"), DEFAULT_TIMEOUT_SECONDS),
        max_attempts=_read_int(_env_name(source_id, "MAX_ATTEMPTS"), DEFAULT_MAX_ATTEMPTS),
        backoff_seconds=_read_float(_env_name(source_id, "BACKOFF"), DEFAULT_BACKOFF_SECONDS),
        backoff_multiplier=_read_float(
            _env_name(source_id, "BACKOFF_MULTIPLIER"), DEFAULT_BACKOFF_MULTIPLIER
        ),
    )


def load_all() -> dict[str, SourceConfig]:
    """네 원천의 설정을 한 번에 읽는다."""
    return {source_id: load_source_config(source_id) for source_id in SOURCE_IDS}


def require_api_key(config: SourceConfig) -> str:
    """인증키를 그때 읽는다. 없으면 **어느 환경변수가 비었는지**를 말한다.

    키 값 자체는 어떤 오류 메시지에도 넣지 않는다 — 오류는 로그로 가고 로그는 남는다.
    """
    if config.api_key_env is None:
        raise MacroConfigError(f"{config.source_id}은(는) 인증키를 쓰지 않는다")
    key = os.environ.get(config.api_key_env, "")
    if key.strip() == "":
        raise MacroConfigError(
            f"{config.source_id} 인증키가 없다. 환경변수 {config.api_key_env}를 채운다"
            " (data/.env.example 참고). 키 없이 개발할 때는 저장된 응답 표본을 쓴다"
        )
    return key
