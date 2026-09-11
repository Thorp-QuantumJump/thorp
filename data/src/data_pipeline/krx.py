"""KRX OPEN API 일봉 수집 (spec 001 FR-004·FR-005·FR-012·FR-014, research.md 결정 1·6).

원천은 KRX OPEN API의 ETF 일별매매정보(``etp/etf_bydd_trd``)이며, 원칙 X가 열거하지 않은
pykrx·yfinance는 쓰지 않는다(research.md 결정 1). 컬럼은 ``date, open, high, low, close,
volume, value``로 표준화한다(``data/CLAUDE.md`` 컬럼 표준, research.md 결정 6).

**이 원천의 성질** — 2026-09-11에 실제 응답을 받아 확인한 것이다. 문서만 보고 추측하면 틀린다.

1. 조회 단위가 종목이 아니라 **날짜**다. ``basDd`` 하루를 부르면 그날 상장된 ETF 전부가 한 번에
   온다(2026-09-10 기준 1,168행). 그래서 한 종목의 긴 이력을 얻으려면 거래일 수만큼 호출해야
   하고, 여러 종목을 함께 모으는 편이 호출 수를 아낀다. :func:`collect_daily_quotes` 가 날짜를
   한 번씩만 부르는 이유다.
2. **휴장일에도 200과 1,167행이 온다.** ``BAS_DD``는 요청한 날짜를 그대로 되돌려 주고 가격
   필드만 빈 문자열이다(2026-09-06 일요일 확인). 행 수만 보고 거래일을 판정하면 휴장일에
   행을 만들게 되므로, 거래일 판정은 이 원천이 아니라 지수 자료가 맡는다
   (:mod:`data_pipeline.index`, contracts/market-dataset.md ※1).
3. **체결이 없던 날은 시가·고가·저가가 ``"0"``이고 종가만 값이 있다.** 거래소가 기준가를 종가
   자리에 낸다(475270 종목의 2026-09-08·09-10 확인). 그날은 체결 가격이 없으므로
   :class:`DailyQuote` 가 ``open``·``high``·``low``를 ``None``으로 담고, 무엇으로 채울지는
   :mod:`data_pipeline.gaps` 가 확정된 규칙(직전 종가)으로 정한다.
4. 자료는 **2010-01-04까지** 거슬러 올라간다(같은 날 확인, ETF 50종목). constitution
   「데이터 원천의 역할」이 적어 둔 범위와 같다.
5. 서비스별로 활용 승인이 따로 필요하다. 승인되지 않은 서비스는
   ``{"respCode": "401", "respMsg": "Unauthorized API Call"}``을 돌려준다. 종목기본정보
   (``sto/stk_isu_base_info``)가 현재 그 상태라 **상장일·상장폐지일을 이 원천에서 받을 수 없다.**
   그래서 상장 구간은 :class:`ListingWindow` 로 밖에서 받고(대응표가 소유한다, T005),
   관측으로 얻은 경계는 :func:`observe_listing_boundaries` 가 따로 내놓아 대조에만 쓴다.

인증키는 ``.env``의 ``KRX_API_KEY``에서만 읽는다(FR-012). 코드·로그·예외 메시지에 키 값을
남기지 않는다.
"""

from __future__ import annotations

import json
import os
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Final, Protocol

import pandas as pd
import requests
from dotenv import load_dotenv

#: 표준 컬럼 순서. ``data/CLAUDE.md`` 컬럼 표준이며 ``value``는 거래대금(원)이다.
OHLCV_COLUMNS: Final[tuple[str, ...]] = (
    "date",
    "open",
    "high",
    "low",
    "close",
    "volume",
    "value",
)

KRX_API_BASE_URL: Final[str] = "https://data-dbg.krx.co.kr/svc/apis"

#: ETF 일별매매정보. (서비스 묶음, 엔드포인트) 쌍이다.
ETF_DAILY_SERVICE: Final[tuple[str, str]] = ("etp", "etf_bydd_trd")

#: 응답 본문이 자료를 담는 키. 이 원천의 모든 서비스가 같은 이름을 쓴다.
RESPONSE_ROWS_KEY: Final[str] = "OutBlock_1"

#: 종목 식별자 형식. ``engine_input.py``·마이그레이션의 ``{6자리}.XKRX``와 같다.
INSTRUMENT_ID_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[0-9]{6}\.XKRX\Z")
ISSUE_CODE_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[0-9]{6}\Z")

_HTTP_UNAUTHORIZED: Final[int] = 401
_HTTP_TOO_MANY_REQUESTS: Final[int] = 429

#: 이용 한도로 읽을 응답 표시. **개발 중 한도에 걸리지 않아 실제 응답을 보지 못했다.**
#: 그래서 확인된 HTTP 429를 주된 근거로 삼고, 아래 낱말은 **200이 아닌 응답에만** 보조로 쓴다
#: (:func:`_rows_from_response` 의 판정 순서 참고). 처리 경로 자체는
#: ``--simulate-quota-exhausted``(:class:`QuotaLimitedSource`)로 재현해 검증한다.
#:
#: 낱말은 **한도로만 읽히는 것**으로 한정한다. ``초과``·``quota``·``traffic`` 같은 넓은 낱말을
#: 넣었더니 ``조회 기간을 초과하였습니다`` 같은 인자 오류까지 한도로 분류되었고, 한도 오류는
#: 수집이 일부러 삼켜 중단으로 처리하므로 거짓 사유를 달고 되돌릴 수 없는 부분 갱신본이
#: 발행된다. 실제 한도 응답을 확인하면 이 목록을 그 값 하나로 좁힌다.
_QUOTA_MARKERS: Final[tuple[str, ...]] = (
    "too many requests",
    "rate limit",
    "요청 한도",
    "이용 한도",
    "호출 한도",
    "일일 허용량",
    "트래픽 초과",
)

_DEFAULT_TIMEOUT_SECONDS: Final[float] = 30.0


class KrxError(RuntimeError):
    """KRX OPEN API 수집 과정의 오류."""


class KrxConfigError(KrxError):
    """인증키 같은 실행 환경이 갖춰지지 않았다. 값이 아니라 환경 문제다."""


class KrxAuthError(KrxError):
    """인증키가 없거나 그 서비스에 활용 승인이 없다 (respCode 401)."""


class KrxQuotaExhaustedError(KrxError):
    """이용 한도에 걸렸다. 그날 수집을 중단하고 부분 갱신으로 발행한다 (FR-014)."""


class KrxResponseError(KrxError):
    """응답이 약속한 모양이 아니다. 값을 지어내지 않고 알린다."""


class ListingBoundaryError(KrxError):
    """상장 구간 밖의 가격 행이 나왔다 (FR-005, SC-003)."""


# ---------------------------------------------------------------------------
# 인증키
# ---------------------------------------------------------------------------


def api_key_from_env(env_path: Path | None = None) -> str:
    """``.env``의 ``KRX_API_KEY``를 읽는다. 키를 코드에 두지 않는다 (FR-012).

    ``env_path``를 명시하면 그 파일의 값이 이미 내보내진 환경 변수를 이긴다.
    :func:`data_pipeline.repository.database_url_from_env` 와 같은 관례이며, 없는 경로를
    넘기면 조용히 넘어가지 않고 알린다.

    오류 메시지에 키 값을 넣지 않는다. 비어 있다는 사실만 알린다.
    """
    if env_path is None:
        load_dotenv()
    else:
        if not env_path.exists():
            raise KrxConfigError(f"지정한 환경 파일이 없다: {env_path}")
        load_dotenv(env_path, override=True)
    key = (os.environ.get("KRX_API_KEY") or "").strip()
    if not key:
        raise KrxConfigError(
            "KRX_API_KEY가 비어 있다. data/.env.example을 data/.env로 복사해 값을 채운다"
        )
    return key


# ---------------------------------------------------------------------------
# 원천 호출
# ---------------------------------------------------------------------------


class DailyRowSource(Protocol):
    """하루치 원천 응답을 돌려주는 것. 테스트는 픽스처를 읽는 구현으로 바꿔 끼운다."""

    def rows(self, group: str, endpoint: str, bas_dd: date) -> list[Mapping[str, str]]:
        """``bas_dd`` 하루치 행을 돌려준다. 휴장일도 행이 올 수 있다."""


def _looks_like_quota(text: str) -> bool:
    lowered = text.lower()
    return any(marker in lowered for marker in _QUOTA_MARKERS)


@dataclass
class KrxOpenApiClient:
    """KRX OPEN API HTTP 클라이언트. 키는 헤더로만 보내고 로그에 남기지 않는다."""

    api_key: str
    base_url: str = KRX_API_BASE_URL
    timeout: float = _DEFAULT_TIMEOUT_SECONDS
    session: requests.Session | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.api_key, str) or not self.api_key.strip():
            raise KrxConfigError("api_key가 비어 있다")

    @classmethod
    def from_env(cls, env_path: Path | None = None, **kwargs: Any) -> KrxOpenApiClient:
        return cls(api_key=api_key_from_env(env_path), **kwargs)

    def rows(self, group: str, endpoint: str, bas_dd: date) -> list[Mapping[str, str]]:
        url = f"{self.base_url.rstrip('/')}/{group}/{endpoint}"
        getter = self.session.get if self.session is not None else requests.get
        response = getter(
            url,
            params={"basDd": f"{bas_dd:%Y%m%d}"},
            headers={"AUTH_KEY": self.api_key},
            timeout=self.timeout,
        )
        return _rows_from_response(group, endpoint, bas_dd, response)


def _rows_from_response(
    group: str, endpoint: str, bas_dd: date, response: requests.Response
) -> list[Mapping[str, str]]:
    """응답을 행 목록으로 바꾸고 인증·한도 오류를 가려낸다.

    **판정 순서가 중요하다.** 확인된 것부터 보고 추측은 마지막에 둔다.

    1. 401(활용 승인 없음). 2026-09-11에 실제로 받아 본 응답이다
    2. 429(요청 과다). HTTP 상태나 ``respCode``가 그 값이면 한도로 본다
    3. 200이 아닌 응답의 ``respMsg``에 한도를 뜻하는 낱말이 있으면 한도로 본다
    4. 그 밖의 200이 아닌 응답은 :class:`KrxResponseError` 다

    한도로 **잘못** 분류하면 피해가 크다. :func:`collect_daily_quotes` 가 한도 오류를 일부러
    삼켜 중단으로 처리하므로(FR-014), ``--commit``이 붙은 실행은 "이용 한도로 중단했다"는
    사유를 달고 되돌릴 수 없는 부분 갱신본을 발행한다. 실제 원인이 잘못된 요청이면 다시
    실행해도 같은 자리에서 멈추는데 사유는 거짓으로 남는다.

    그래서 3번을 **200이 아닌 응답에만** 적용하고 낱말도 한도로만 읽히는 것으로 좁혔다.
    이전에는 ``초과``만으로도 한도로 보았는데, ``조회 기간을 초과하였습니다`` 같은 인자
    오류가 200으로 오면 그것까지 한도가 된다. 잘못 분류해 조용히 발행하는 편보다 분류하지
    못해 시끄럽게 멈추는 편이 낫다 — 멈추면 발행이 일어나지 않는다.
    """
    where = f"{group}/{endpoint} basDd={bas_dd:%Y%m%d}"
    status = response.status_code
    body: object
    try:
        body = response.json()
    except ValueError:
        body = None

    message = ""
    resp_code = ""
    if isinstance(body, Mapping):
        message = str(body.get("respMsg") or "")
        resp_code = str(body.get("respCode") or "")

    if status == _HTTP_UNAUTHORIZED or resp_code == str(_HTTP_UNAUTHORIZED):
        raise KrxAuthError(
            f"인증키가 없거나 이 서비스의 활용 승인이 없다: {where} ({message}). "
            "KRX OPEN API는 서비스별로 승인을 받는다"
        )
    if status == _HTTP_TOO_MANY_REQUESTS or resp_code == str(_HTTP_TOO_MANY_REQUESTS):
        raise KrxQuotaExhaustedError(f"이용 한도에 걸렸다: {where} (HTTP {status} {message})")
    if status != 200:
        if _looks_like_quota(message):
            raise KrxQuotaExhaustedError(f"이용 한도에 걸렸다: {where} (HTTP {status} {message})")
        raise KrxResponseError(f"예상하지 못한 응답이다: {where} (HTTP {status} {message})")
    if not isinstance(body, Mapping):
        raise KrxResponseError(f"응답 본문이 매핑이 아니다: {where} ({type(body).__name__})")
    if RESPONSE_ROWS_KEY not in body:
        raise KrxResponseError(
            f"응답에 {RESPONSE_ROWS_KEY}가 없다: {where} (키: {sorted(body)}) {message}"
        )
    rows = body[RESPONSE_ROWS_KEY]
    if not isinstance(rows, list):
        raise KrxResponseError(f"{RESPONSE_ROWS_KEY}가 배열이 아니다: {where}")
    for position, row in enumerate(rows):
        if not isinstance(row, Mapping):
            raise KrxResponseError(f"{where}의 {position}번째 행이 매핑이 아니다")
    return list(rows)


@dataclass
class QuotaLimitedSource:
    """``--simulate-quota-exhausted``용 덮개. ``allowed_calls`` 번 뒤에는 한도 오류를 낸다.

    이용 한도에 걸렸을 때의 처리(중단 → ``complete = false`` + ``failure_reason``)를
    실제 한도를 소모하지 않고 재현하기 위한 것이다(FR-014, quickstart 시나리오 7).
    """

    inner: DailyRowSource
    allowed_calls: int = 1
    calls: int = 0

    def rows(self, group: str, endpoint: str, bas_dd: date) -> list[Mapping[str, str]]:
        if self.calls >= self.allowed_calls:
            raise KrxQuotaExhaustedError(
                f"이용 한도 도달을 모의했다: 허용 호출 {self.allowed_calls}회를 넘겼다 "
                f"({group}/{endpoint} basDd={bas_dd:%Y%m%d})"
            )
        self.calls += 1
        return self.inner.rows(group, endpoint, bas_dd)


@dataclass
class RawArchivingSource:
    """원천 응답 원본을 ``raw/``에 남기면서 통과시킨다 (research.md 결정 8).

    재수집 없이 다시 정제하기 위한 보관이며 방향은 ``raw → processed → PostgreSQL``
    한 방향뿐이다. 이 덮개는 쓰기만 하고 읽어 되돌리지 않는다.
    """

    inner: DailyRowSource
    root: Path

    def rows(self, group: str, endpoint: str, bas_dd: date) -> list[Mapping[str, str]]:
        rows = self.inner.rows(group, endpoint, bas_dd)
        target = self.root / group / endpoint / f"{bas_dd:%Y%m%d}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps({RESPONSE_ROWS_KEY: rows}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return rows


# ---------------------------------------------------------------------------
# 값 변환
# ---------------------------------------------------------------------------


def instrument_id_for(issue_code: str) -> str:
    """``069500`` → ``069500.XKRX``. 마이그레이션의 형식 CHECK와 같은 모양으로 만든다."""
    if not isinstance(issue_code, str) or ISSUE_CODE_PATTERN.match(issue_code.strip()) is None:
        raise KrxResponseError(f"종목코드가 6자리 숫자가 아니다: {issue_code!r}")
    return f"{issue_code.strip()}.XKRX"


def issue_code_for(instrument_id: str) -> str:
    """``069500.XKRX`` → ``069500``. 원천 응답과 맞추기 위한 반대 변환이다."""
    if not isinstance(instrument_id, str) or INSTRUMENT_ID_PATTERN.match(instrument_id) is None:
        raise KrxResponseError(f"종목 식별자가 {{6자리}}.XKRX 형식이 아니다: {instrument_id!r}")
    return instrument_id[:6]


def _blank(text: object) -> bool:
    return text is None or (isinstance(text, str) and not text.strip())


def parse_optional_decimal(text: object, field_name: str, where: str) -> Decimal | None:
    """가격 문자열을 :class:`Decimal` 로 바꾼다. 빈 문자열과 ``0``은 값이 없다는 뜻이다.

    빈 문자열은 휴장일, ``0``은 체결이 없던 날의 시가·고가·저가에서 나온다(둘 다 실제 응답에서
    확인했다). 어느 쪽도 가격이 아니므로 ``None``을 돌려주고, 채우는 판단은 부르는 쪽에 맡긴다.
    """
    if _blank(text):
        return None
    try:
        parsed = Decimal(str(text).replace(",", "").strip())
    except (InvalidOperation, ValueError) as error:
        raise KrxResponseError(f"{where}의 {field_name}이(가) 숫자가 아니다: {text!r}") from error
    if parsed < 0:
        raise KrxResponseError(f"{where}의 {field_name}이(가) 음수다: {text!r}")
    return parsed if parsed > 0 else None


def parse_optional_int(text: object, field_name: str, where: str) -> int | None:
    if _blank(text):
        return None
    try:
        parsed = int(Decimal(str(text).replace(",", "").strip()))
    except (InvalidOperation, ValueError) as error:
        raise KrxResponseError(f"{where}의 {field_name}이(가) 정수가 아니다: {text!r}") from error
    if parsed < 0:
        raise KrxResponseError(f"{where}의 {field_name}이(가) 음수다: {text!r}")
    return parsed


@dataclass(frozen=True)
class DailyQuote:
    """하루치 원천 시세 한 건. 원천이 준 것만 담고 빈 값을 채우지 않는다.

    ``open``·``high``·``low``가 ``None``이면 그날 **체결이 없었다.** 원천은 그때 세 값을
    ``"0"``으로 주고 종가 자리에는 거래소 기준가를 낸다. 확정된 규칙은 그날 OHLC 네 값을 모두
    직전 종가로 채우는 것이므로(contracts/market-dataset.md 「거래정지 처리」), 기준가를 그대로
    쓰지 않고 :mod:`data_pipeline.gaps` 가 직전 종가로 바꾼다. 여기서 바꾸지 않는 이유는
    직전 거래일을 알려면 거래일 달력이 필요하고, 그 달력은 지수 자료가 소유하기 때문이다.
    """

    instrument_id: str
    date: date
    close: Decimal
    open: Decimal | None
    high: Decimal | None
    low: Decimal | None
    volume: int
    value: int

    @property
    def traded(self) -> bool:
        """그날 체결이 있었는가. 시가·고가·저가가 모두 공시되었는지로 본다."""
        return self.open is not None and self.high is not None and self.low is not None


def normalize_etf_row(row: Mapping[str, str], *, expected_date: date) -> DailyQuote | None:
    """ETF 일별매매정보 한 행을 :class:`DailyQuote` 로 바꾼다.

    ``None``을 돌려주는 경우는 그날 그 종목의 공시값이 아예 없을 때다(휴장일의 빈 문자열).
    행을 만들지 않는 것이 결측의 표현이므로 0으로 채우지 않는다(FR-005, 원칙 VI).
    """
    where = f"etf_bydd_trd {expected_date:%Y%m%d} {row.get('ISU_CD', '?')}"
    basis = str(row.get("BAS_DD") or "").strip()
    if basis and basis != f"{expected_date:%Y%m%d}":
        # 원천은 요청한 날짜를 그대로 되돌려 준다. 다르면 엉뚱한 날짜를 그 날짜의 자료로
        # 적재하는 셈이라 조용히 넘기지 않는다.
        raise KrxResponseError(f"{where}: 응답의 BAS_DD가 요청한 날짜와 다르다 ({basis})")

    instrument_id = instrument_id_for(str(row["ISU_CD"]))
    close = parse_optional_decimal(row.get("TDD_CLSPRC"), "TDD_CLSPRC", where)
    if close is None:
        return None
    volume = parse_optional_int(row.get("ACC_TRDVOL"), "ACC_TRDVOL", where)
    value = parse_optional_int(row.get("ACC_TRDVAL"), "ACC_TRDVAL", where)
    if volume is None or value is None:
        raise KrxResponseError(f"{where}: 종가는 있는데 거래량·거래대금이 비어 있다")

    quote = DailyQuote(
        instrument_id=instrument_id,
        date=expected_date,
        close=close,
        open=parse_optional_decimal(row.get("TDD_OPNPRC"), "TDD_OPNPRC", where),
        high=parse_optional_decimal(row.get("TDD_HGPRC"), "TDD_HGPRC", where),
        low=parse_optional_decimal(row.get("TDD_LWPRC"), "TDD_LWPRC", where),
        volume=volume,
        value=value,
    )
    if quote.traded:
        high, low, opened = quote.high, quote.low, quote.open
        assert high is not None and low is not None and opened is not None
        if high < max(opened, low, close) or low > min(opened, high, close):
            raise KrxResponseError(
                f"{where}: 고가·저가가 OHLC 상하한을 벗어난다 "
                f"(o={opened} h={high} l={low} c={close})"
            )
    return quote


# ---------------------------------------------------------------------------
# 상장 구간
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ListingWindow:
    """한 종목의 상장 구간. 대응표가 소유하는 값이며 수집은 이것을 지킨다 (FR-005).

    원천의 종목기본정보 서비스에 활용 승인이 없어 상장일을 원천에서 받을 수 없으므로
    (이 모듈 문서 5번), 이 값은 섹터-ETF 대응표(``representative_etf``)에서 온다.
    """

    instrument_id: str
    listed_on: date
    delisted_on: date | None = None

    def __post_init__(self) -> None:
        if INSTRUMENT_ID_PATTERN.match(self.instrument_id) is None:
            raise ListingBoundaryError(
                f"종목 식별자가 {{6자리}}.XKRX 형식이 아니다: {self.instrument_id!r}"
            )
        if not isinstance(self.listed_on, date):
            raise ListingBoundaryError(f"listed_on은 date여야 한다: {self.listed_on!r}")
        if self.delisted_on is not None:
            if not isinstance(self.delisted_on, date):
                raise ListingBoundaryError(f"delisted_on은 date여야 한다: {self.delisted_on!r}")
            if self.delisted_on <= self.listed_on:
                raise ListingBoundaryError(
                    f"delisted_on은 listed_on보다 뒤여야 한다: {self.instrument_id} "
                    f"{self.listed_on} → {self.delisted_on}"
                )

    def contains(self, day: date) -> bool:
        if day < self.listed_on:
            return False
        return self.delisted_on is None or day <= self.delisted_on


def windows_by_instrument(windows: Iterable[ListingWindow]) -> dict[str, ListingWindow]:
    """상장 구간을 종목별로 모은다. 같은 종목이 두 번 오면 거부한다."""
    mapped: dict[str, ListingWindow] = {}
    for window in windows:
        if window.instrument_id in mapped:
            raise ListingBoundaryError(f"상장 구간이 두 번 들어 있다: {window.instrument_id}")
        mapped[window.instrument_id] = window
    return mapped


def enforce_listing_window(quotes: Sequence[DailyQuote], window: ListingWindow) -> None:
    """상장 구간 밖의 시세가 있으면 거부한다 (FR-005, SC-003).

    빼 버리지 않고 오류를 내는 이유: 상장 전 날짜에 가격이 나왔다면 대응표의 상장일이 틀렸거나
    원천이 다른 종목을 준 것이다. 조용히 버리면 그 사실이 드러나지 않고, 상장일이 틀린 채로
    자료 버전이 발행되면 트리거가 UPDATE·DELETE를 막아 되돌릴 수 없다.
    """
    for quote in quotes:
        if quote.instrument_id != window.instrument_id:
            raise ListingBoundaryError(
                f"다른 종목의 시세가 섞여 있다: {window.instrument_id} ≠ {quote.instrument_id}"
            )
        if quote.date < window.listed_on:
            raise ListingBoundaryError(
                f"상장 전 가격 행이다: {quote.instrument_id} {quote.date} "
                f"< listed_on {window.listed_on} (FR-005, SC-003)"
            )
        if window.delisted_on is not None and quote.date > window.delisted_on:
            raise ListingBoundaryError(
                f"상장폐지 이후 가격 행이다: {quote.instrument_id} {quote.date} "
                f"> delisted_on {window.delisted_on}"
            )


def observe_listing_boundaries(quotes: Iterable[DailyQuote]) -> dict[str, tuple[date, date]]:
    """수집한 시세에서 종목별 첫 날짜와 마지막 날짜를 뽑는다.

    상장일·상장폐지일이 **아니다.** 조회한 구간 안에서 관측된 경계일 뿐이므로 대응표의
    상장일과 대조하거나(quickstart 시나리오 2), 후보의 확보 가능 기간을 재는 데(T014)만 쓴다.
    """
    boundaries: dict[str, tuple[date, date]] = {}
    for quote in quotes:
        first, last = boundaries.get(quote.instrument_id, (quote.date, quote.date))
        boundaries[quote.instrument_id] = (min(first, quote.date), max(last, quote.date))
    return boundaries


# ---------------------------------------------------------------------------
# 수집
# ---------------------------------------------------------------------------


@dataclass
class KrxCollection:
    """수집 결과. 모으지 못한 날짜와 중단 사유까지 함께 담는다 (FR-014).

    ``complete``가 false면 부분 갱신이며 ``failure_reason``이 반드시 있다. ``last_collected``는
    실제로 수집한 마지막 거래일이고, 자료 버전의 데이터 마감일은 요청한 날짜가 아니라 이 값으로
    정한다(quickstart 시나리오 7).

    ``unsupported_codes``는 저장 스키마가 담을 수 없는 종목코드다. 아래
    :func:`collect_daily_quotes` 의 설명을 참고한다.
    """

    quotes: list[DailyQuote] = field(default_factory=list)
    collected_dates: list[date] = field(default_factory=list)
    requested_dates: list[date] = field(default_factory=list)
    complete: bool = True
    failure_reason: str | None = None
    unsupported_codes: set[str] = field(default_factory=set)

    @property
    def last_collected(self) -> date | None:
        return max(self.collected_dates) if self.collected_dates else None

    @property
    def missed_dates(self) -> list[date]:
        collected = set(self.collected_dates)
        return [day for day in self.requested_dates if day not in collected]

    def for_instrument(self, instrument_id: str) -> list[DailyQuote]:
        """한 종목의 시세를 날짜 오름차순으로 돌려준다."""
        picked = [quote for quote in self.quotes if quote.instrument_id == instrument_id]
        picked.sort(key=lambda quote: quote.date)
        return picked

    @property
    def instrument_ids(self) -> list[str]:
        return sorted({quote.instrument_id for quote in self.quotes})


def collect_daily_quotes(
    source: DailyRowSource,
    trading_days: Sequence[date],
    *,
    instrument_ids: Iterable[str] | None = None,
    windows: Iterable[ListingWindow] | None = None,
) -> KrxCollection:
    """거래일마다 한 번씩 호출해 ETF 일별매매정보를 모은다.

    ``trading_days``는 지수 자료가 만든 거래일 달력이어야 한다. 이 원천은 휴장일에도 행을
    되돌려 주므로 달력 없이 날짜를 훑으면 휴장일 행을 만들게 된다
    (contracts/market-dataset.md ※1).

    ``instrument_ids``를 주면 그 종목만 남긴다. 호출 수는 달라지지 않는다 — 조회 단위가
    날짜여서 하루를 부르면 그날 상장된 ETF 전부가 함께 오기 때문이다.

    이용 한도에 걸리면 **중단하고 그때까지 모은 것을 돌려준다**(FR-014). 예외를 그대로
    올리면 부분 수집물이 사라지고, 사라지면 어디까지 모았는지 기록할 수 없다.

    **6자리 숫자가 아닌 종목코드가 섞여 온다.** 2026-09-11에 실제 응답에서 확인한 것으로,
    그날 값이 있는 ETF 1,168종목 가운데 304종목(26%)의 코드가 ``0184E0``처럼 숫자와 영문이
    섞인 6글자였다. 이 표기는 2025년부터 나타나 빠르게 늘고 있다(2024-01 0종목 → 2025-01
    6종목 → 2026-01 178종목 → 2026-09 304종목). 저장 스키마의
    ``ck_daily_price_instrument_id_format``과 ``engine_input.py``가 ``{6자리}.XKRX``만
    허용하므로 이 종목들은 담을 수 없다. 그래서 수집을 멈추지도 조용히 버리지도 않고
    :attr:`KrxCollection.unsupported_codes` 에 모아 둔다. 형식을 넓히는 판단은 저장 스키마를
    소유한 쪽(T003)의 몫이며 이 묶음에서 마이그레이션을 고치지 않는다.
    """
    group, endpoint = ETF_DAILY_SERVICE
    wanted_codes = (
        {issue_code_for(item) if "." in item else item.strip() for item in instrument_ids}
        if instrument_ids is not None
        else None
    )
    by_instrument = windows_by_instrument(windows or [])
    ordered = sorted(set(trading_days))
    collection = KrxCollection(requested_dates=list(ordered))

    for day in ordered:
        try:
            rows = source.rows(group, endpoint, day)
        except KrxQuotaExhaustedError as error:
            collection.complete = False
            collection.failure_reason = (
                f"KRX OPEN API 이용 한도로 {day:%Y-%m-%d} 수집을 중단했다: {error}"
            )
            break
        for row in rows:
            code = str(row.get("ISU_CD") or "").strip()
            if not code:
                raise KrxResponseError(f"{group}/{endpoint} {day:%Y%m%d} 행에 ISU_CD가 없다")
            if ISSUE_CODE_PATTERN.match(code) is None:
                # 6자리 숫자가 아닌 종목코드다(예: ``0184E0``). 저장 스키마의
                # ``ck_daily_price_instrument_id_format``과 ``engine_input.py``가 모두
                # ``{6자리}.XKRX``만 허용하므로 담을 수 없다.
                #
                # **찾는 종목만 걸러내기보다 이 검사를 먼저 둔다.** 진입점은 ``--instruments``를
                # 필수로 받고 그 값은 이미 6자리로 검사되므로, 걸러내기를 먼저 하면 이 갈래에
                # 아무도 도달하지 못해 모아 둔 목록이 늘 비고 보고도 나오지 않는다. 담을 수
                # 없는 종목이 원천의 26%(2026-09-10 기준 304종목)라는 사실은 후보를 고르는
                # 쪽에 필요한 정보라, 찾는 종목이 무엇이든 관측한 대로 알린다.
                collection.unsupported_codes.add(code)
                continue
            # 식별자로 바꾸기 전에 원천 코드로 걸러낸다. 하루를 부르면 그날 상장된 ETF
            # 전부가 오므로 찾는 종목만 남긴다.
            if wanted_codes is not None and code not in wanted_codes:
                continue
            instrument_id = instrument_id_for(code)
            quote = normalize_etf_row(row, expected_date=day)
            if quote is None:
                # 그날 그 종목의 공시값이 없다(휴장일의 빈 문자열). 상장 구간 밖의 날짜에도
                # 원천이 이런 껍데기 행을 주므로, 값이 없는 행은 경계 판정의 대상이 아니다.
                continue
            window = by_instrument.get(instrument_id)
            if window is not None and not window.contains(day):
                # **값이 있는 행이 상장 구간 밖에 있으면 거른 뒤 넘어가지 않고 알린다.**
                # 상장 전 날짜에 가격이 나왔다는 것은 대응표의 상장일이 틀렸거나 원천이 다른
                # 종목을 준 것이다. 조용히 버리면 그 사실이 드러나지 않고, 상장일이 틀린 채로
                # 자료 버전이 발행되면 트리거가 UPDATE·DELETE를 막아 되돌릴 수 없다
                # (FR-005, SC-003). 여러 종목을 한꺼번에 모아도 각 종목은 자기 상장 구간
                # 밖의 날짜에 아예 나타나지 않으므로, 이 갈래를 타는 것은 실제 모순뿐이다.
                enforce_listing_window([quote], window)
            collection.quotes.append(quote)
        collection.collected_dates.append(day)

    for instrument_id, window in by_instrument.items():
        enforce_listing_window(collection.for_instrument(instrument_id), window)
    return collection


def quotes_to_frame(quotes: Sequence[DailyQuote]) -> pd.DataFrame:
    """체결이 있던 날만 표준 컬럼 데이터프레임으로 만든다.

    체결이 없던 날을 여기 담지 않는 이유는 표준 컬럼에 그 상태를 표현할 자리가 없기 때문이다.
    0으로 채우면 OHLC 양수 조건을 어기고 결측으로 채우면 값을 지어내는 셈이다(원칙 VI).
    그날의 처리는 :func:`data_pipeline.gaps.build_daily_prices` 가 직전 종가로 맡는다.
    """
    records = [
        {
            "date": quote.date,
            "open": quote.open,
            "high": quote.high,
            "low": quote.low,
            "close": quote.close,
            "volume": quote.volume,
            "value": quote.value,
        }
        for quote in sorted(quotes, key=lambda item: item.date)
        if quote.traded
    ]
    frame = pd.DataFrame.from_records(records, columns=list(OHLCV_COLUMNS))
    return frame.reset_index(drop=True)


def fetch_ohlcv(
    ticker: str,
    start: date,
    end: date,
    *,
    source: DailyRowSource,
    trading_days: Sequence[date],
) -> pd.DataFrame:
    """단일 종목 일봉 OHLCV 조회. 컬럼은 :data:`OHLCV_COLUMNS` 로 표준화한다.

    ``trading_days``를 반드시 받는다. 원천이 휴장일에도 행을 주므로 달력 없이는 거래일을
    가릴 수 없고, 달력의 출처는 지수 자료다(:func:`data_pipeline.index.build_trading_calendar`).
    달력을 기본값으로 만들어 주면 부르는 쪽이 그 사실을 모르는 채로 휴장일 행을 만들 수 있다.

    돌려주는 프레임에는 **체결이 있던 날만** 있다. 체결이 없던 날과 거래정지일의 처리는
    :mod:`data_pipeline.gaps` 가 맡는다.
    """
    if end < start:
        raise KrxError(f"end가 start보다 앞이다: {start} → {end}")
    instrument_id = ticker if INSTRUMENT_ID_PATTERN.match(ticker) else instrument_id_for(ticker)
    days = [day for day in sorted(set(trading_days)) if start <= day <= end]
    collection = collect_daily_quotes(source, days, instrument_ids=[instrument_id])
    if not collection.complete:
        raise KrxQuotaExhaustedError(collection.failure_reason or "이용 한도로 수집을 중단했다")
    return quotes_to_frame(collection.for_instrument(instrument_id))


# ---------------------------------------------------------------------------
# processed/ 저장
# ---------------------------------------------------------------------------


def write_processed_parquet(
    frame: pd.DataFrame,
    root: Path,
    *,
    dataset: str,
    instrument_id: str,
) -> list[Path]:
    """정제 산출물을 ``자산/연도`` 파티션 Parquet으로 쓴다 (research.md 결정 8).

    파티션 구조는 추후 Spark가 그대로 읽는 전제이므로 임의로 바꾸지 않는다(``data/CLAUDE.md``).
    서비스는 이 파일을 읽지 않는다 — 읽는 곳은 PostgreSQL 하나다. 같은 파티션을 다시 쓰면
    덮어써서 재실행이 멱등하다.
    """
    if "date" not in frame.columns:
        raise KrxError(f"date 컬럼이 없어 연도 파티션을 만들 수 없다: {sorted(frame.columns)}")
    written: list[Path] = []
    if frame.empty:
        return written
    years = pd.to_datetime(frame["date"]).dt.year
    for year, part in frame.groupby(years, sort=True):
        target = root / dataset / f"instrument_id={instrument_id}" / f"year={int(year)}"
        target.mkdir(parents=True, exist_ok=True)
        path = target / "part-0.parquet"
        part.drop(columns=[]).reset_index(drop=True).to_parquet(path, index=False)
        written.append(path)
    return written
