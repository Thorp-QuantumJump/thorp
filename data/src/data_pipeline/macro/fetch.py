"""원천에 실제로 요청을 거는 자리 (spec 008 FR-011b).

**어댑터는 HTTP 를 모른다.** 경로·인자를 만들고 응답을 읽을 뿐이라, 그 둘 사이를 잇는 전송이
여기 하나로 모인다. 어댑터마다 요청을 걸면 재시도·타임아웃·오류 분류가 제각각이 되고, 그러면
한 원천은 영영 매달리고 다른 원천은 한 번 만에 포기한다.

**인증키는 이 모듈 밖으로 나가지 않는다**(FR-021). 경로에 키를 끼우는 것도 여기서만 하고, 오류
메시지에는 키를 지운 경로를 싣는다 — 오류는 로그로 가고 로그는 남는다.

**HTTP 상태를 다시 걸 것과 아닌 것으로 가른다.** 5xx·시간 초과·끊긴 연결은 일시 오류이고,
4xx 는 몇 번을 걸어도 같은 답이 온다. 429 만 따로 두는 이유는 한도 도달이 실패가 아니라 "여기까지"
이기 때문이다 — 그 시점까지 받은 것은 남기고 멈춘다(FR-011).

테스트는 :class:`Fetcher` 를 갈아 끼워 저장된 표본을 읽는다. 그래서 인증키 없이도 수집 경로
전체를 시험할 수 있다(FR-022).
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import TYPE_CHECKING, Any, Final, Protocol
from urllib.parse import quote, quote_plus, urlencode

from data_pipeline.macro.config import API_KEY_ENV_NAMES, SourceConfig, require_api_key
from data_pipeline.macro.sources.base import (
    PermanentSourceError,
    QuotaExhaustedError,
    TransientSourceError,
    fetch_with_retry,
)

#: 키 자리 표시. 어댑터가 만든 경로의 ``{key}`` 를 전송 직전에만 채운다.
KEY_PLACEHOLDER: Final[str] = "{key}"

_QUOTA_STATUS: Final[int] = 429
_RETRYABLE_STATUS: Final[frozenset[int]] = frozenset({408, 500, 502, 503, 504})


if TYPE_CHECKING:
    from data_pipeline.macro.sources.base import RateLimiter


class Fetcher(Protocol):
    """요청 하나를 보내고 본문을 문자열로 돌려준다."""

    def get(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> str: ...


class RequestsFetcher:
    """`requests` 로 실제 요청을 건다. 운영 경로다."""

    def __init__(self, timeout_seconds: float) -> None:
        self._timeout = timeout_seconds

    def get(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> str:
        import requests  # 지연 임포트 — 표본만 읽는 테스트는 이 의존을 건드리지 않는다

        try:
            response = requests.get(
                url, params=params, headers=headers, timeout=self._timeout
            )
        except requests.Timeout as error:
            raise TransientSourceError(
                f"시간 초과: {_safe(url)} / {_safe_cause(error)}"
            ) from None
        except requests.RequestException as error:
            raise TransientSourceError(
                f"요청 실패: {_safe(url)} / {_safe_cause(error)}"
            ) from None

        status = response.status_code
        if status == _QUOTA_STATUS:
            raise QuotaExhaustedError(f"호출 한도에 닿았다: {_safe(url)}")
        if status in _RETRYABLE_STATUS:
            raise TransientSourceError(f"원천 오류 {status}: {_safe(url)}")
        if status >= 400:
            # 인증 거부·없는 경로가 여기다. 관세청 옛 주소는 키가 멀쩡해도 이 자리로 온다.
            raise PermanentSourceError(f"원천이 거절했다 {status}: {_safe(url)}")
        return response.text


#: 오류 메시지에서 키를 가릴 때 쓸 자리표.
_REDACTED: Final[str] = "***"


def _secret_values() -> tuple[str, ...]:
    """지금 환경에 들어 있는 인증키 값들.

    **길이로 짐작하지 않고 실제 값으로 지운다.** 길이 규칙은 짧은 키를 그냥 통과시키고,
    긴 통계표 코드는 애꿎게 가린다 — 둘 다 틀리는 방향이다.
    """
    return tuple(
        value
        for name in API_KEY_ENV_NAMES
        if (value := os.environ.get(name, "").strip())
    )


def redact(text: str) -> str:
    """인증키가 섞였을 수 있는 문자열을 오류·로그에 실을 모양으로 바꾼다 (FR-021).

    URL 인코딩된 형태도 함께 지운다 — 관세청 키는 ``%2F`` 를 품은 채 주소에 들어가므로 원문만
    지우면 인코딩된 쪽이 남는다.
    """
    cleaned = text
    for secret in _secret_values():
        for form in (secret, quote(secret, safe=""), quote_plus(secret)):
            if form:
                cleaned = cleaned.replace(form, _REDACTED)
    return cleaned


def _safe(url: str) -> str:
    """오류 메시지에 실을 경로. 키가 들어갈 수 있는 자리를 지운다."""
    if KEY_PLACEHOLDER in url:
        return url
    return redact(url)


def _safe_cause(error: Exception) -> str:
    """원인 예외를 오류 메시지에 실을 한 줄로.

    **원인을 ``__cause__`` 로 매달지 않는다.** requests 는 실패한 주소를 메시지에 통째로
    담는데, 그 주소에는 질의 인자로 들어간 인증키가 있다. 연쇄로 매달면 우리 메시지를 아무리
    깨끗이 써도 트레이스백이 찍히는 순간 키가 로그에 남는다(FR-021). 대신 예외 이름과 지운
    메시지를 여기에 옮겨 담아 진단에 필요한 것은 잃지 않는다.
    """
    return f"{type(error).__name__}: {redact(str(error))}"


def fetch_text(
    fetcher: Fetcher,
    config: SourceConfig,
    path: str = "",
    params: dict[str, str] | None = None,
    *,
    needs_key: bool = True,
    key_param: str | None = None,
    headers: dict[str, str] | None = None,
) -> str:
    """설정된 재시도 안에서 한 요청을 끝낸다.

    키를 넣는 방법이 원천마다 다르다. 한국은행은 경로 안에 박고, 국가통계포털과 관세청은
    질의 인자로 받으며, 경제협력개발기구는 키가 없다. 그 차이를 부르는 쪽이 알 필요는 없다.
    """
    request_params = dict(params or {})
    request_path = path
    if needs_key and config.api_key_env is not None:
        key = require_api_key(config)
        if key_param is not None:
            request_params[key_param] = key
        elif KEY_PLACEHOLDER in path:
            request_path = path.replace(KEY_PLACEHOLDER, key)
        else:
            raise PermanentSourceError(
                f"{config.source_id}: 키를 넣을 자리가 없다. 경로의 {KEY_PLACEHOLDER} 나"
                " key_param 중 하나가 있어야 한다"
            )

    url = f"{config.base_url}/{request_path.lstrip('/')}" if request_path else config.base_url
    return fetch_with_retry(
        lambda: fetcher.get(url, request_params or None, headers), config
    )


def fetch_json(
    fetcher: Fetcher,
    config: SourceConfig,
    path: str = "",
    params: dict[str, str] | None = None,
    *,
    needs_key: bool = True,
    key_param: str | None = None,
) -> Any:
    """JSON 을 돌려주는 원천용. 본문이 JSON 이 아니면 그 자리에서 멈춘다.

    원천이 오류를 HTML 로 돌려주는 일이 있는데, 그것을 빈 결과로 넘기면 수집이 성공한 것처럼
    보인다.
    """
    body = fetch_text(
        fetcher, config, path, params, needs_key=needs_key, key_param=key_param
    )
    try:
        return json.loads(body)
    except json.JSONDecodeError as error:
        raise PermanentSourceError(
            f"{config.source_id}: 응답이 JSON 이 아니다 ({body[:120]!r})"
        ) from error


def describe(config: SourceConfig, path: str, params: dict[str, str] | None) -> str:
    """무엇을 부를지 사람이 읽을 수 있게 적는다. **키는 넣지 않는다.**"""
    query = f"?{urlencode(params)}" if params else ""
    return f"{config.base_url}/{path.lstrip('/')}{query}"


class ThrottledFetcher:
    """원천의 호출 한도를 지키며 보내는 :class:`Fetcher` 겉면 (FR-017a).

    한도를 **호출 지점마다** 거는 대신 여기 한 곳에 두는 이유는, 지점이 다섯이고 주택가격은
    사슬의 표마다 한 번씩 더 부르기 때문이다. 한 군데라도 빠뜨리면 한도는 지켜지는 척만 한다.

    셀 상한은 여기서 보지 않는다 — 요청이 몇 셀인지는 원천마다 세는 법이 다르고 그것을 아는
    것은 어댑터다. 어댑터가 :meth:`RateLimiter.check_request_size` 로 따로 묻는다.
    """

    def __init__(self, inner: Fetcher, limiter: RateLimiter) -> None:
        self._inner = inner
        self._limiter = limiter

    def get(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> str:
        self._limiter.acquire()
        return self._inner.get(url, params, headers)


class SampleFetcher:
    """저장된 예시 응답을 읽는 :class:`Fetcher` (FR-022).

    **개발 프로필에서만 쓰인다.** 인증키가 없거나 원천을 부를 수 없을 때 파이프라인 전체를
    돌려 보기 위한 것이며, 운영 경로에는 이 클래스로 가는 길이 없다(`scripts/ingest_macro.py`
    의 `build_fetcher` 참고).

    **없는 표본을 빈 응답으로 대신하지 않는다.** 그렇게 하면 표본을 빠뜨린 지표가 "원천이 값을
    주지 않았다"로 보여, 준비가 덜 된 것과 원천이 비어 있는 것이 같은 모양이 된다.

    파일은 ``{원천}/{이름}.json|xml`` 로 찾는다. 어느 파일을 고를지는 요청 주소와 인자에 든
    식별자로 정하며, 실제 원천의 주소 규칙을 흉내 내지 않는다 — 흉내 내면 원천이 주소를 바꿀
    때 예시까지 함께 깨져 개발이 멈춘다.
    """

    def __init__(self, root: Path) -> None:
        self._root = root

    def get(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> str:
        haystack = f"{url} {params or {}}"
        for path in sorted(self._root.rglob("*")):
            if path.suffix not in {".json", ".xml"} or not path.is_file():
                continue
            if path.stem in haystack or path.parent.name in haystack:
                return path.read_text(encoding="utf-8")
        raise SampleDataMissing(
            f"예시 자료에서 이 요청에 맞는 파일을 찾지 못했다: {url} {params or {}}."
            f" {self._root} 아래에 표본을 둔다 — 빈 응답으로 대신하지 않는다"
        )


class SampleDataMissing(FileNotFoundError):
    """개발 프로필인데 그 요청에 맞는 예시 응답이 없다 (FR-022)."""
