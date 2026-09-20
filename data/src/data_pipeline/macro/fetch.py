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
from typing import Any, Final, Protocol
from urllib.parse import urlencode

from data_pipeline.macro.config import SourceConfig, require_api_key
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


class Fetcher(Protocol):
    """요청 하나를 보내고 본문을 문자열로 돌려준다."""

    def get(self, url: str, params: dict[str, str] | None = None) -> str: ...


class RequestsFetcher:
    """`requests` 로 실제 요청을 건다. 운영 경로다."""

    def __init__(self, timeout_seconds: float) -> None:
        self._timeout = timeout_seconds

    def get(self, url: str, params: dict[str, str] | None = None) -> str:
        import requests  # 지연 임포트 — 표본만 읽는 테스트는 이 의존을 건드리지 않는다

        try:
            response = requests.get(url, params=params, timeout=self._timeout)
        except requests.Timeout as error:
            raise TransientSourceError(f"시간 초과: {_safe(url)}") from error
        except requests.RequestException as error:
            raise TransientSourceError(f"요청 실패: {_safe(url)}") from error

        status = response.status_code
        if status == _QUOTA_STATUS:
            raise QuotaExhaustedError(f"호출 한도에 닿았다: {_safe(url)}")
        if status in _RETRYABLE_STATUS:
            raise TransientSourceError(f"원천 오류 {status}: {_safe(url)}")
        if status >= 400:
            # 인증 거부·없는 경로가 여기다. 관세청 옛 주소는 키가 멀쩡해도 이 자리로 온다.
            raise PermanentSourceError(f"원천이 거절했다 {status}: {_safe(url)}")
        return response.text


def _safe(url: str) -> str:
    """오류 메시지에 실을 경로. 키가 들어갈 수 있는 자리를 지운다."""
    if KEY_PLACEHOLDER in url:
        return url
    parts = url.split("/")
    return "/".join(part if len(part) < 20 else "***" for part in parts)


def fetch_text(
    fetcher: Fetcher,
    config: SourceConfig,
    path: str = "",
    params: dict[str, str] | None = None,
    *,
    needs_key: bool = True,
    key_param: str | None = None,
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
        lambda: fetcher.get(url, request_params or None), config
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
