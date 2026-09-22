"""지표 등록표 — 17개 지표가 어디서 오고 무엇으로 바뀌는지의 단일 출처 (spec 008 FR-002).

값의 근거는 [pd11-source-verification.md]다. 2026-09-17에 17개 전부를 실제로 조회해 통계표·항목·
단위·확보 구간·작성기관을 확인했고, 여기 있는 것은 그 결과를 옮긴 것이다. **추정으로 채운 칸이
없다** — 확인하지 못한 항목은 값을 지어내지 않고 ``None`` 으로 두며 그것을 채우는 작업을 적어 둔다.

**어댑터는 여기서 읽을 뿐 스스로 고르지 않는다**(FR-005). 한국은행과 국가통계포털이 같은 지표를
줄 때 주 공급원을 고르는 것은 등록표이고, 어댑터가 고르면 같은 지표가 실행마다 다른 원천에서 와도
값이 왜 달라졌는지 되짚을 곳이 없다.

작성기관과 제공기관이 다를 수 있다(FR-016). 두바이유는 한국은행이 제공하지만 작성기관은
국제통화기금이고, 주택가격은 국가통계포털이 제공하지만 작성기관은 한국부동산원이다. 이용 조건은
**작성기관 쪽을 먼저 본다** — 제공 창구가 어디든 저작권은 작성기관에 있다.

아직 비워 둔 칸과 그것을 채울 작업::

    minimum_effective_scale  010 소유 (PD-10). 008은 채우지 않는다 (FR-015)
    trading_calendar         월 평균 경로에 닿는 지표에만 필요하다. `TRADING_CALENDAR_*` 참고
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, replace
from datetime import date
from decimal import Decimal
from typing import Final

from data_pipeline.macro.config import (
    SOURCE_CUSTOMS,
    SOURCE_ECOS,
    SOURCE_KOSIS,
    SOURCE_OECD,
    load_source_config,
)

#: 7개 분야. 009 계약과 같은 식별자다(data-model §1).
FIELD_PRODUCTION: Final[str] = "production"
FIELD_CONSUMPTION_INVESTMENT: Final[str] = "consumption_investment"
FIELD_EXTERNAL_TRADE: Final[str] = "external_trade"
FIELD_LABOR: Final[str] = "labor"
FIELD_PRICES_COMMODITIES: Final[str] = "prices_commodities"
FIELD_FINANCE_HOUSING: Final[str] = "finance_housing"
FIELD_OVERSEAS: Final[str] = "overseas"

FIELD_IDS: Final[tuple[str, ...]] = (
    FIELD_PRODUCTION,
    FIELD_CONSUMPTION_INVESTMENT,
    FIELD_EXTERNAL_TRADE,
    FIELD_LABOR,
    FIELD_PRICES_COMMODITIES,
    FIELD_FINANCE_HOUSING,
    FIELD_OVERSEAS,
)

#: 원천이 값을 주는 주기. **어댑터가 통계표 코드로 추측하지 않는다** — 새 일별 계열이
#: 들어올 때 코드를 고쳐야 하는 자리가 생기고, 그 자리는 등록표가 들어야 한다(FR-005).
CYCLE_DAILY: Final[str] = "D"
CYCLE_MONTHLY: Final[str] = "M"

CYCLE_IDS: Final[tuple[str, ...]] = (CYCLE_DAILY, CYCLE_MONTHLY)

#: 변환 여섯 (FR-001 표). 앞의 넷은 계산하고 뒤의 둘은 원천 값을 그대로 쓴다.
TRANSFORM_YOY: Final[str] = "YOY_GROWTH"
TRANSFORM_MOM: Final[str] = "MOM_GROWTH"
TRANSFORM_MONTH_END: Final[str] = "MONTH_END"
TRANSFORM_MONTH_AVERAGE: Final[str] = "MONTH_AVERAGE"
TRANSFORM_OFFICIAL_AMOUNT: Final[str] = "OFFICIAL_AMOUNT"
TRANSFORM_SEASONAL_RATE: Final[str] = "SEASONAL_RATE"

#: 권리 판정 상태 (data-model §1). UNVERIFIED 만 발행 보류 대상이다 (FR-016a).
REVIEW_VERIFIED: Final[str] = "VERIFIED"
REVIEW_PENDING_RIGHTS: Final[str] = "PENDING_RIGHTS"
REVIEW_UNVERIFIED: Final[str] = "UNVERIFIED"

#: 이용 허락 상태. 스키마의 `ck_macro_indicator_license_status` 와 같은 값이다.
#:
#: ``SUSPENDED`` 는 **원천이 조건을 바꾸거나 권한을 거둬들인 상태**다(FR-016b). 그 지표의 새
#: 수집과 새 자료 버전 편입을 멈추지만 **이미 발행한 버전은 지우지 않는다** — 지우면 그 버전을
#: 참조한 과거 검색 결과의 재현이 깨진다. 중단은 "앞으로 안 쓴다"이지 "없던 일로 한다"가 아니다.
LICENSE_ALLOWED: Final[str] = "ALLOWED"
LICENSE_RESTRICTED: Final[str] = "RESTRICTED"
LICENSE_SUSPENDED: Final[str] = "SUSPENDED"
LICENSE_UNKNOWN: Final[str] = "UNKNOWN"

LICENSE_STATUSES: Final[tuple[str, ...]] = (
    LICENSE_ALLOWED,
    LICENSE_RESTRICTED,
    LICENSE_SUSPENDED,
    LICENSE_UNKNOWN,
)

#: 새 수집과 새 자료 버전에 넣을 수 있는 이용 허락 상태.
COLLECTIBLE_LICENSE_STATUSES: Final[frozenset[str]] = frozenset({LICENSE_ALLOWED})

#: 작성기관이 상업적 이용을 허락했는가 (FR-016, FR-018).
#:
#: **셋으로 나눈다. 불리언이면 "모른다"를 적을 수 없다.** 제한된 것과 모르는 것은 우리가 할
#: 일이 정반대다 — 제한된 계열은 어느 경로로도 내보내지 않고(FR-018), 모르는 계열은 확인
#: 대기로 두고 수집·공개를 이어간다(FR-016a). 둘을 거짓 하나로 묶으면 두바이유가 내보내기에서
#: 빠지고, 참 하나로 묶으면 제한 계열이 파일로 새어 나간다.
COMMERCIAL_ALLOWED: Final[str] = "ALLOWED"
COMMERCIAL_RESTRICTED: Final[str] = "RESTRICTED"
COMMERCIAL_UNKNOWN: Final[str] = "UNKNOWN"

COMMERCIAL_USE_STATES: Final[tuple[str, ...]] = (
    COMMERCIAL_ALLOWED,
    COMMERCIAL_RESTRICTED,
    COMMERCIAL_UNKNOWN,
)

#: 정기 재조회 구간 N (FR-017). **잠정값이며 미확인으로 닫는다** — 근거는 아래에 있다.
DEFAULT_REFRESH_MONTHS_N: Final[int] = 3

#: N 을 왜 3으로 두고 왜 여기서 확정하지 않는지 (T073, FR-017, constitution 미확인 항목 처리).
#:
#: **확정하지 못한 이유.** N 은 "원천이 과거 몇 달치를 함께 정정하는가"를 묻는 값이고, 그것은
#: 정기 수집을 여러 달 돌려 같은 달의 값이 언제 바뀌는지 세어야 비로소 보인다. 지금 가진 것은
#: 한 시점의 전 구간 한 벌뿐이라 개정을 한 건도 관측하지 못했다. 관측 없이 지표마다 다른 수를
#: 적으면 그것은 확정이 아니라 추측이고, 등록표에 추측을 적으면 나중에 그 칸이 근거 있는 값처럼
#: 읽힌다.
#:
#: **왜 3인가.** 관세청은 매월 15일경 전월까지의 자료를 정정한다고 안내한다(pd11 §4). 3개월은
#: 그 주기보다 짧지 않다. 국가통계포털·한국은행·경제협력개발기구는 정정 주기를 수치로 밝히지
#: 않아 근거로 쓸 것이 없으므로 같은 값을 쓴다.
#:
#: **확정에 필요한 것.** 정기 수집을 최소 N+3 개월 돌린 뒤, `macro_raw_revision` 에서 지표별로
#: (a) N 안에서 잡힌 개정과 (b) N 밖에서 뒤늦게 발견된 개정을 센다. (b) 가 이어지는 지표는 N 을
#: 늘린다(FR-017).
#:
#: **의심스러우면 늘린다.** 틀리는 방향이 대칭이 아니다 — N 이 길면 이미 확정된 달을 몇 번 더
#: 받을 뿐이고 그 비용은 호출 몇 번이다. N 이 짧으면 정정을 통째로 놓치고, 놓친 값은 다음 전
#: 구간 재적재까지 틀린 채로 남으며 그 사실이 어디에도 드러나지 않는다.
REFRESH_MONTHS_BASIS: Final[str] = (
    "잠정값 3개월. 관세청의 매월 정정 안내(pd11 §4)보다 짧지 않게 잡은 값이며, 나머지 세 원천은"
    " 정정 주기를 수치로 밝히지 않아 같은 값을 쓴다. 실제 개정 관행은 정기 수집을 여러 달 돌려"
    " macro_raw_revision 에서 N 밖 개정을 세어야 확정되므로 이번 범위에서는 미확인으로 닫는다."
    " 조정 방향은 한쪽으로만 연다 — 의심스러우면 늘린다. N 이 길면 이미 확정된 달을 다시 받는"
    " 호출 몇 번이 비용이지만, N 이 짧으면 정정을 놓치고 놓친 사실조차 드러나지 않는다."
)


class RegistryError(ValueError):
    """등록표가 지켜야 할 불변식을 어겼다."""


#: 거래일 달력 식별자 (FR-006b). **월 평균 경로에 닿는 지표만 이 값이 필요하다.**
#:
#: `transform.monthly` 에서 달력을 읽는 곳은 `fold_month_average` 하나뿐이고, 거기에 닿으려면
#: 변환이 월 평균이면서 원천이 일별로 줘야 한다. 월 기말은 원천이 장이 선 날만 행을 주므로
#: 그 달의 마지막 행이 곧 마지막 거래일이고, 달력 없이 규칙이 성립한다(FR-006a).
#:
#: **지금 17개 중 월 평균 경로에 닿는 지표는 없다.** 두바이유는 변환이 월 평균이지만 한국은행이
#: 월 평균을 이미 내 주므로 주기가 월별이고 그대로 통과한다. 국고채만 일별인데 변환이 월 기말이다.
#: 그래서 이 칸이 비어 있어도 결측이 생기지 않으며, 실제로 2026-09-20 전 구간 수집에서 두 지표
#: 모두 결측 0건이었다. 비어 있는 칸을 채우는 대신 **닿는 지표가 생기면 등록이 거부되도록**
#: `validate` 에 불변식을 두었다.
#:
#: 달력 식별자는 상수로 미리 두지 않는다 — 쓰는 지표가 없는데 이름만 있으면 다음 사람이 그중
#: 하나를 확인 없이 고른다. 값은 그 지표를 더할 때 근거와 함께 정한다.
TRADING_CALENDAR_UNVERIFIED: Final[str] = (
    "채권·외환 계열의 휴장일이 KRX 주식 달력과 같은지는 확인하지 못했다. 같다고 가정하고 KRX"
    " 달력을 걸면, 두 달력이 하루라도 다른 달마다 평균이 조용히 다른 표본으로 만들어진다."
    " 원천의 휴장일 안내를 먼저 확인한다"
)

#: 원천별 호출 한도·재시도 (FR-011b(1), FR-017a). **등록표가 들고 어댑터가 따른다.**
#:
#: 재시도 수치의 출처는 `config` 다 — 환경변수로 덮을 수 있어야 하고, 그 읽기는 접속 설정의
#: 일이기 때문이다. 등록표는 그 값을 참조하면서 **원천이 공표한 한도**를 함께 든다. 한도는
#: 환경변수로 바뀔 성질이 아니라 원천이 정하는 사실이라 여기가 제자리다.
#:
#: 한도를 수치로 공표하지 않는 원천은 '수치 한도 없음'으로 **확인된 것**이지 미확인이 아니다
#: (FR-017a). 둘을 구분하지 않으면 확인된 원천까지 정기 수집에서 빠진다.
@dataclass(frozen=True)
class SourcePolicy:
    """원천 하나의 호출 규칙. 재시도는 `config` 값을 참조하고 한도는 여기서 든다."""

    source_id: str
    #: 원천이 공표한 호출 한도. 수치로 밝히지 않으면 그 사실을 문장으로 적는다.
    rate_limit: str
    #: 요청 하나가 담을 수 있는 개월 수. `None` 이면 상한이 없다.
    max_months_per_request: int | None
    #: 한도의 출처. 어디서 읽었는지 없으면 나중에 이 값이 추측인지 확인값인지 갈리지 않는다.
    basis: str
    #: 분당 호출 상한. `None` 이면 원천이 수치로 밝히지 않았다 (FR-017a).
    #:
    #: 위의 `rate_limit` 은 사람이 읽는 문장이고 이것은 **기계가 지키는 수치**다. 문장만 두면
    #: 한도가 적혀 있을 뿐 아무도 지키지 않는다.
    max_calls_per_minute: int | None = None
    #: 요청 하나가 담을 수 있는 셀 수. `None` 이면 상한이 없다.
    max_cells_per_request: int | None = None
    #: 연달아 부를 때 최소로 둘 간격(초).
    #:
    #: 수치 한도를 밝히지 않은 원천에 쓴다. 한도가 없다는 말은 마음껏 불러도 된다는 뜻이
    #: 아니라 **과다 호출 시 차단할 수 있다**는 뜻이다(pd11). 차단되면 그 지표의 자료가
    #: 끊기므로 보수적으로 벌린다. 근거 있는 수가 아니라 "적당히"를 판정 가능한 수로 바꾼
    #: 것이며 실측 뒤 조정한다(원칙 IX).
    min_interval_seconds: float = 0.0

    @property
    def max_attempts(self) -> int:
        return load_source_config(self.source_id).max_attempts

    @property
    def backoff_seconds(self) -> tuple[float, ...]:
        """재시도 사이에 기다릴 초를 차례대로. 기본은 1·2초(3회 시도라 간격은 둘)다."""
        loaded = load_source_config(self.source_id)
        return tuple(loaded.backoff_for(n) for n in range(1, loaded.max_attempts))


SOURCE_POLICIES: Final[dict[str, SourcePolicy]] = {
    SOURCE_ECOS: SourcePolicy(
        source_id=SOURCE_ECOS,
        rate_limit="수치 한도 없음, 과다 호출 시 제한 가능",
        min_interval_seconds=0.2,
        max_months_per_request=None,
        basis="한국은행 오픈API 이용안내에 호출 수 상한이 수치로 없다 (pd11 §1)",
    ),
    SOURCE_KOSIS: SourcePolicy(
        source_id=SOURCE_KOSIS,
        rate_limit="분당 200회, 요청당 40,000셀",
        max_months_per_request=None,
        basis="국가통계포털 공유서비스 이용약관의 공표 한도 (FR-017, pd11 §2)",
        max_calls_per_minute=200,
        max_cells_per_request=40_000,
    ),
    SOURCE_CUSTOMS: SourcePolicy(
        source_id=SOURCE_CUSTOMS,
        rate_limit="수치 한도 없음, 과다 호출 시 제한 가능",
        min_interval_seconds=0.2,
        max_months_per_request=12,
        basis="공공데이터포털이 호출 수를 수치로 밝히지 않는다. 조회 기간 12개월 상한은"
        " 실제 응답으로 확인했다 (pd11 §4)",
    ),
    SOURCE_OECD: SourcePolicy(
        source_id=SOURCE_OECD,
        rate_limit="수치 한도 없음, 과다 호출 시 제한 가능",
        min_interval_seconds=0.2,
        max_months_per_request=None,
        basis="SDMX 공개 종단점에 공표된 수치 한도가 없다 (pd11 §3)",
    ),
}


def source_policy(source_id: str) -> SourcePolicy:
    """원천 하나의 호출 규칙. 등록표에 없는 원천은 쓰지 않는다."""
    try:
        return SOURCE_POLICIES[source_id]
    except KeyError:
        raise RegistryError(
            f"등록표에 호출 규칙이 없는 원천이다: {source_id!r}."
            f" 쓸 수 있는 값은 {', '.join(sorted(SOURCE_POLICIES))}"
        ) from None


@dataclass(frozen=True)
class IndicatorDefinition:
    """지표 하나의 등록 정보. 컬럼 이름은 `macro_indicator_definition` 과 같다."""

    indicator_id: str
    field_id: str
    primary_source: str
    table_code: str | None
    item_code: str | None
    #: 국가통계포털의 기관 코드. 통계표 id 만으로는 표가 특정되지 않는다 — 생산·물가는 통계청
    #: (101), 주택가격은 한국부동산원(408)이고 기관이 틀리면 없는 표로 거절된다(pd11 §2).
    org_id: str | None
    #: 통계표 안에서 계열 하나를 고르는 분류. ``(차원 이름, 코드)`` 를 순서대로 담는다.
    #:
    #: **항목 코드만으로는 계열이 하나로 좁혀지지 않는다.** 광공업생산의 ``T10`` 은 시도별·
    #: 산업별로 갈라져 있어 ``objL1=00`` 전국과 ``objL2=0`` 총지수를 함께 줘야 비로소 우리가
    #: 아는 그 계열이 된다. 이 값이 비면 어댑터는 어느 계열을 받을지 스스로 정하게 되는데,
    #: 그것은 등록표가 할 일이다(FR-005).
    classification: tuple[tuple[str, str], ...]
    raw_unit: str
    transformed_unit: str
    transform_rule: str
    agency: str
    attribution: str
    license_status: str
    license_review_status: str
    #: 작성기관의 상업적 이용 허락 (:data:`COMMERCIAL_USE_STATES` 중 하나).
    #:
    #: **제공기관이 아니라 작성기관을 본다**(FR-016). 한국은행이 내보내는 두바이유의 저작권은
    #: 국제통화기금에 있고, 제공 창구가 열려 있다는 사실이 이용 허락은 아니다.
    commercial_use: str
    #: 원자료를 그대로 제3자에게 넘기는 것에 걸린 제한 (FR-018).
    #:
    #: 우리는 어느 지표든 원자료 재배포를 하지 않으므로 이 값이 동작을 바꾸지는 않는다. 다만
    #: **조건을 읽고 적어 두었다는 사실**이 남아야 나중에 경로를 늘릴 때 다시 뒤지지 않는다.
    redistribution_rule: str
    #: 006 이 파일로 내보낼 때 함께 실어야 할 이용 범위 문구 (FR-018).
    #:
    #: 화면은 출처·작성기관만 붙이면 되지만 내보내기는 원자료가 파일로 빠져나가는 경로라
    #: 받는 사람이 무엇을 할 수 있는지 함께 가야 한다. 지표마다 조건이 달라 한 문구로 묶을 수
    #: 없다 — 묶으면 가장 느슨한 쪽이나 가장 빡빡한 쪽 하나가 전부를 대표하게 된다.
    export_notice: str
    #: 이용 조건을 마지막으로 확인한 날 (FR-018a). 확인하지 않았으면 ``None`` 이다.
    license_verified_on: date | None
    #: 이 날까지는 다시 확인해야 한다 (FR-018a).
    #:
    #: 한국은행 인증키는 발급일로부터 2년이고 만료되면 수집이 끊긴다. 나머지 원천은 만기가
    #: 없지만 약관·서비스 명세가 개정되면 조건이 달라질 수 있어 주기를 둔다. **지나도 수집을
    #: 막지 않는다** — 막으면 확인이 하루 늦은 것이 자료를 끊는 일이 되어, 확인하라는 신호가
    #: 자료를 끊는 벌이 된다. 대신 재확인 대상으로 드러난다(:func:`reverification_due`).
    license_reverify_by: date | None
    series_start: str
    revision_history_available: bool
    #: 원천이 **몇 달 뒤늦게** 값을 내는가. 오늘이 2026-09 일 때 최신 대상월이 2026-07 이면 2다.
    #:
    #: 전 구간 적재가 끝났는지 판정하려면 "몇 달치가 와야 하는가"를 알아야 하는데(FR-011b(3)),
    #: 그 수는 상수가 아니라 달마다 늘어난다. 그래서 개수를 적는 대신 **지연을 적고 개수는
    #: 계산한다** — `expected_month_count` 가 그 일을 한다. 개수를 적어 두면 다음 달에 바로
    #: 틀리고, 틀린 기대값은 미완료를 정상으로 보이게 만든다.
    #:
    #: 값의 출처는 2026-09-20 전 구간 수집의 실제 최신 대상월이다(원칙 IX). 추정이 아니다.
    publication_lag_months: int
    release_rule: str | None = None
    break_link_rule: str | None = None
    missing_rule: str | None = None
    refresh_months_n: int = DEFAULT_REFRESH_MONTHS_N
    #: 원천이 값을 주는 주기. 17개 중 국고채만 일별이고 나머지는 월별이다.
    #:
    #: 변환이 이 값을 본다. 월 기말·월 평균은 원천이 일별로 줄 때만 접는 일이 필요하고,
    #: 증가율은 월 값끼리 견주는 것이라 일별 계열에 그대로 걸 수 없다. 관측의 기간 표기를
    #: 훑어 주기를 추측하지 않는 이유는, 추측은 자료가 비었을 때 답을 내지 못하고 원천이
    #: 주기를 바꿨을 때도 조용히 따라가기 때문이다.
    source_cycle: str = CYCLE_MONTHLY
    #: 개편으로 나뉜 계열을 잇는 통계표 사슬. 비어 있으면 표 하나로 받는 보통 지표다.
    #:
    #: **비어 있지 않으면 보통 경로로 받으면 안 된다.** 위의 ``table_code`` 는 사슬의 첫
    #: 표이자 현행 표일 뿐이라, 그것만 받으면 ``series_start`` 가 말하는 구간의 일부만
    #: 온다 — 주택가격은 2000-01 이 아니라 2021-06 부터만 온다. 그런데 응답은 정상이고
    #: 값도 멀쩡해서 짧아진 줄 모른다. 그래서 어댑터가 이 값을 보고 거절한다(FR-010).
    link_chain: tuple[LinkedTable, ...] = ()
    #: 이용이 중단된 날과 사유 (FR-016b). 중단이면 **둘 다 있어야 한다**.
    #:
    #: 사유 없이 지표만 사라지면 나중에 되돌릴 근거가 없다. 스키마의
    #: `ck_macro_indicator_suspension` 이 같은 것을 DB 쪽에서 막는다.
    license_suspended_on: date | None = None
    license_suspended_reason: str | None = None
    # 아래 셋은 이 작업의 범위가 아니다. 모듈 docstring 참고.
    published_precision: int | None = None
    minimum_effective_scale: str | None = None
    trading_calendar: str | None = None


_KOSIS_ATTRIBUTION: Final[str] = "국가통계포털(KOSIS)"
_ECOS_ATTRIBUTION: Final[str] = "한국은행 경제통계시스템(ECOS)"
_STATISTICS_KOREA: Final[str] = "통계청"

_NO_RELEASE_DATE: Final[str] = (
    "원천이 관측치별 발표일을 주지 않는다. 수신 시각을 발표일로 쓰지 않는다"
)
_TABLE_UPDATED_AT: Final[str] = "LST_CHN_DE 는 통계표 갱신일이며 관측치별 발표일이 아니다"

#: 이용 조건을 확인한 날 (FR-018a). pd11 이 네 원천의 약관 원문을 읽은 날이다.
LICENSE_VERIFIED_ON: Final[date] = date(2026, 9, 17)

#: 한국은행 인증키 만료 전 재확인 시한 (FR-018a, pd11 §1 약관 제4조 ③).
#:
#: 이용기간은 발급일로부터 2년이다. 발급일을 기록해 두지 않았으므로 **확인일에서 2년**으로
#: 잡는다 — 실제 발급일은 그보다 앞이라 이 시한은 안전한 쪽으로 틀린다. 정확한 날짜를 알게
#: 되면 그때 좁힌다.
ECOS_REVERIFY_BY: Final[date] = date(2028, 9, 17)

#: 만기가 없는 원천의 재확인 주기 (FR-018a).
#:
#: 약관·서비스 명세가 개정되면 조건이 달라질 수 있는데 개정을 통보받을 경로가 없다. 그래서
#: 시한을 두고 사람이 들여다보게 한다. 1년은 근거 있는 값이 아니라 "언젠가"를 판정 가능한
#: 날짜로 바꾼 것이다(원칙 IX).
ANNUAL_REVERIFY_BY: Final[date] = date(2027, 9, 17)

#: 원천별 재배포 조건과 내보내기 문구 (FR-018). 근거는 pd11 의 약관 원문 확인이다.
_ECOS_REDISTRIBUTION: Final[str] = (
    "출처 표시 의무(약관 제7조 ②). 상업적 이용을 제한하는 통계는 작성기관 승인이 필요하다"
    "(제5조 ①). 원자료를 그대로 제3자에게 넘기지 않는다"
)
_KOSIS_REDISTRIBUTION: Final[str] = (
    "출처 표시 조건으로 이용한다. 라이선스 제한이 있는 국제통계·북한통계는 예외인데 여기 10개는"
    " 해당하지 않는다. 원자료를 그대로 제3자에게 넘기지 않는다"
)
_CUSTOMS_REDISTRIBUTION: Final[str] = (
    "공공데이터포털 이용허락범위 제한 없음. 출처는 관세청이다"
)
_OECD_REDISTRIBUTION: Final[str] = (
    "재배포·하위 라이선스에도 같은 출처 표시 의무를 승계시켜야 한다(약관 2024-07-01 개정)."
    " 표기 형식은 OECD (연도), (데이터셋 이름), (출처) DOI 또는 URL (접근일)"
)

_ECOS_EXPORT_NOTICE: Final[str] = (
    "출처: 한국은행 경제통계시스템(ECOS). 상업적 이용 시 작성기관의 조건을 따른다"
)
_KOSIS_EXPORT_NOTICE: Final[str] = (
    "출처: 국가통계포털(KOSIS). 출처를 표시하면 상업적 이용을 포함해 자유롭게 쓸 수 있다"
)
_CUSTOMS_EXPORT_NOTICE: Final[str] = (
    "출처: 관세청 수출입총괄. 이용허락범위 제한이 없다"
)
_OECD_EXPORT_NOTICE: Final[str] = (
    "출처: OECD Composite Leading Indicators. 상업적 이용을 포함해 쓸 수 있으나 다시 넘길 때도"
    " 같은 출처 표시를 함께 실어야 한다"
)


#: 국가통계포털 기관 코드 (pd11 §2).
ORG_STATISTICS_KOREA: Final[str] = "101"
ORG_REAL_ESTATE_BOARD: Final[str] = "408"

@dataclass(frozen=True)
class LinkedTable:
    """개편으로 나뉜 계열의 통계표 하나. 연결 사슬의 한 마디다 (FR-010)."""

    #: 이 표가 쓰는 기준월. 연결 기록이 이 값으로 두 표를 가리킨다.
    base_month: str
    org_id: str
    table_code: str
    item_code: str
    classification: tuple[tuple[str, str], ...]
    #: 2026-09-18 에 실제로 받아 확인한 구간. **확인 기록이지 판정 기준이 아니다** —
    #: ``coverage_end`` 는 원천이 새 달을 낼 때마다 밀리므로 적재 완료 판정에 쓰면 매달
    #: 미완료가 된다. 판정 기준이 될 기대 대상월 수는 T077 이 따로 넣는다(FR-011b).
    coverage_start: str
    coverage_end: str


#: 주택가격 연결 사슬. **새 기준월부터 늘어놓는다** — 앞의 표가 기준이 되고 뒤로 갈수록
#: 계수가 누적된다.
#:
#: 이 목록이 등록표에 있어야 하는 이유는, 어느 표를 어떤 순서로 잇느냐가 곧 2000-01 부터의
#: 값을 정하기 때문이다. 목록이 코드나 테스트에만 있으면 연결 규칙이 등록표 버전에 매이지
#: 않고, 표를 하나 갈아끼웠을 때 자료 버전이 왜 달라졌는지 되짚을 곳이 없다(FR-003).
#:
#: 2011.6 표는 분류 체계가 다르다 — 앞의 표들이 ``objL1`` 에 유형, ``objL2`` 에 지역을 두는
#: 반면 이 표는 ``objL1`` 이 지역(전국), ``objL2`` 가 주택유형(종합)이다. 같은 코드를 넣으면
#: 없는 분류로 거절된다.
HOUSING_LINK_CHAIN: Final[tuple[LinkedTable, ...]] = (
    LinkedTable(
        base_month="2026-06", org_id=ORG_REAL_ESTATE_BOARD, table_code="DT_30404_C012",
        item_code="sales", classification=(("objL1", "00"), ("objL2", "a0")),
        coverage_start="2021-06", coverage_end="2026-07",
    ),
    LinkedTable(
        base_month="2021-06", org_id=ORG_REAL_ESTATE_BOARD, table_code="DT_40803_N0001",
        item_code="sales", classification=(("objL1", "00"), ("objL2", "a0")),
        coverage_start="2003-11", coverage_end="2025-03",
    ),
    LinkedTable(
        base_month="2011-06", org_id=ORG_REAL_ESTATE_BOARD, table_code="DT_304N_04_00001",
        item_code="16304304_01",
        classification=(("objL1", "1530430404A"), ("objL2", "15304A304_04_021")),
        coverage_start="2000-01", coverage_end="2012-12",
    ),
)

#: 연결에 쓰이지 않는 판본. 구간이 현행 표와 겹치기만 해 전 구간을 넓히지 못하지만, 같은
#: 구간을 다른 기준월로 적은 자료라 개정 대조에 쓴다(T052).
HOUSING_REVISION_VINTAGES: Final[tuple[LinkedTable, ...]] = (
    LinkedTable(
        base_month="2026-01", org_id=ORG_REAL_ESTATE_BOARD, table_code="DT_30404_B012",
        item_code="sales", classification=(("objL1", "00"), ("objL2", "a0")),
        coverage_start="2021-06", coverage_end="2026-06",
    ),
    LinkedTable(
        base_month="2025-03", org_id=ORG_REAL_ESTATE_BOARD, table_code="DT_30404_A012",
        item_code="sales", classification=(("objL1", "00"), ("objL2", "a0")),
        coverage_start="2021-06", coverage_end="2026-01",
    ),
)

#: 주택가격의 개편 연결 규칙 (FR-010).
#:
#: 괄호 안의 수치는 2026-09-18 표본으로 실제 이어 본 결과다. 계수는 자료를 받을 때마다 다시
#: 재므로 여기 적힌 값은 **확인 기록이지 계산에 쓰는 상수가 아니다** — 원천이 개정하면 계수도
#: 달라져야 하고, 상수로 박아 두면 그 변화가 묻힌다. 계산에 쓰는 값은 발행 때 산출해 자료에
#: 남긴다.
_HOUSING_BREAK_LINK_RULE: Final[str] = (
    "기준월별 표 셋을 연쇄 연결한다 (HOUSING_LINK_CHAIN)."
    " 겹침 구간 전체의 평균 비율이 아니라 겹침에서 가장 최근 달 한 곳의 비율로 잇는다 —"
    " 그래야 각 표 안의 전월 대비 변화가 보존된다."
    " 2026-09-18 실측: 2021.6→현행은 2025-03 에서 계수 1.014008342, 겹침 46개월, 비율 편차"
    " 0.0000%(순수 재기준화). 2011.6→현행은 2012-12 에서 계수 0.822651718, 겹침 110개월,"
    " 비율 편차 5.6788%. 뒤엣것은 두 표가 같은 달에 대해 다른 변화를 말한다는 뜻이라"
    " 2012년 이전 수준은 연결 지점에 따라 최대 5.7% 달라진다. 편차를 자료에 함께 남긴다."
)

#: 경제협력개발기구 경기선행지수의 계열 키. **차원을 다 적어야 한다.**
#:
#: DSD_STES 의 차원은 아홉이고 순서는 ``REF_AREA.FREQ.MEASURE.UNIT_MEASURE.ACTIVITY.
#: ADJUSTMENT.TRANSFORMATION.TIME_HORIZ.METHODOLOGY`` 다. 값을 지정하지 않는 차원도 자리는
#: 비워 둔 채 점으로 남겨야 하며, 자리 수가 모자라면 자료가 오지 않고
#: ``403 Not enough key values in query, expecting 9 got 5`` 가 돌아온다.
#:
#: 고른 값은 진폭조정 지수(``ADJUSTMENT=AA``, ``TRANSFORMATION=IX``)다. 원천이 주는 증가율
#: 계열(``TR``/``GY``)은 지수보다 여섯 달 늦어 쓰지 않는다(pd11 §3, FR-019).
_G20_CLI_KEY: Final[str] = "G20.M.LI...AA.IX..H"


def _kosis(
    indicator_id: str,
    field_id: str,
    table_code: str,
    item_code: str,
    classification: tuple[tuple[str, str], ...],
    raw_unit: str,
    transform_rule: str,
    series_start: str,
    publication_lag_months: int,
    published_precision: int,
    *,
    agency: str = _STATISTICS_KOREA,
    org_id: str = ORG_STATISTICS_KOREA,
    transformed_unit: str = "%",
) -> IndicatorDefinition:
    return IndicatorDefinition(
        indicator_id=indicator_id,
        field_id=field_id,
        primary_source=SOURCE_KOSIS,
        table_code=table_code,
        item_code=item_code,
        org_id=org_id,
        classification=classification,
        raw_unit=raw_unit,
        transformed_unit=transformed_unit,
        transform_rule=transform_rule,
        agency=agency,
        attribution=_KOSIS_ATTRIBUTION,
        license_status=LICENSE_ALLOWED,
        license_review_status=REVIEW_VERIFIED,
        commercial_use=COMMERCIAL_ALLOWED,
        redistribution_rule=_KOSIS_REDISTRIBUTION,
        export_notice=_KOSIS_EXPORT_NOTICE,
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ANNUAL_REVERIFY_BY,
        series_start=series_start,
        revision_history_available=True,
        publication_lag_months=publication_lag_months,
        published_precision=published_precision,
        release_rule=_TABLE_UPDATED_AT,
    )


#: 17개 지표. 순서는 FR-001 표를 따른다.
INDICATORS: Final[tuple[IndicatorDefinition, ...]] = (
    # --- 생산 (pd11 §2) ---
    _kosis("all_industry_production", FIELD_PRODUCTION, "DT_1JH20201", "T1",
           (("objL1", "0"),), "2020=100", TRANSFORM_YOY, "2000-01", 2, 1),
    _kosis("mining_manufacturing_production", FIELD_PRODUCTION, "DT_1F02001", "T10",
           (("objL1", "00"), ("objL2", "0")), "2020=100", TRANSFORM_YOY, "1980-01", 2, 1),
    _kosis("service_production", FIELD_PRODUCTION, "DT_1KC2020", "T1",
           (("objL1", "T"),), "2020=100", TRANSFORM_YOY, "2000-01", 2, 1),
    # --- 소비·투자 (pd11 §2) ---
    _kosis("retail_sales_index", FIELD_CONSUMPTION_INVESTMENT, "DT_1K41012", "T1",
           (("objL1", "G0"),), "2020=100", TRANSFORM_YOY, "1995-01", 2, 1),
    # 설비투자지수는 분류가 objL2 하나뿐이다. objL1 을 지어내 채우지 않는다.
    _kosis("facility_investment_index", FIELD_CONSUMPTION_INVESTMENT, "DT_1F70011", "T3",
           (("objL2", "C"),), "2020=100", TRANSFORM_YOY, "1995-02", 2, 1),
    _kosis("construction_completed_constant", FIELD_CONSUMPTION_INVESTMENT, "DT_1G18011", "T10",
           (("objL1", "0"),), "백만원", TRANSFORM_YOY, "1997-07", 2, 0),
    # --- 대외거래 (pd11 §4). 무역수지는 원천이 주는 공식 값이며 수출-수입 파생식이 아니다 ---
    IndicatorDefinition(
        indicator_id="exports", field_id=FIELD_EXTERNAL_TRADE, primary_source=SOURCE_CUSTOMS,
        table_code=None, item_code="expDlr", org_id=None, classification=(),
        raw_unit="미국 달러", transformed_unit="%",
        transform_rule=TRANSFORM_YOY, agency="관세청", attribution="관세청 수출입총괄",
        license_status=LICENSE_ALLOWED, license_review_status=REVIEW_VERIFIED,
        commercial_use=COMMERCIAL_ALLOWED,
        redistribution_rule=_CUSTOMS_REDISTRIBUTION,
        export_notice=_CUSTOMS_EXPORT_NOTICE,
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ANNUAL_REVERIFY_BY,
        series_start="1989-01",
        revision_history_available=False,
        publication_lag_months=0,
        published_precision=0,
        release_rule=_NO_RELEASE_DATE,
        missing_rule="응답의 <year>총계</year> 합계 행은 관측값으로 세지 않는다",
    ),
    IndicatorDefinition(
        indicator_id="imports", field_id=FIELD_EXTERNAL_TRADE, primary_source=SOURCE_CUSTOMS,
        table_code=None, item_code="impDlr", org_id=None, classification=(),
        raw_unit="미국 달러", transformed_unit="%",
        transform_rule=TRANSFORM_YOY, agency="관세청", attribution="관세청 수출입총괄",
        license_status=LICENSE_ALLOWED, license_review_status=REVIEW_VERIFIED,
        commercial_use=COMMERCIAL_ALLOWED,
        redistribution_rule=_CUSTOMS_REDISTRIBUTION,
        export_notice=_CUSTOMS_EXPORT_NOTICE,
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ANNUAL_REVERIFY_BY,
        series_start="1989-01",
        revision_history_available=False,
        publication_lag_months=0,
        published_precision=0,
        release_rule=_NO_RELEASE_DATE,
        missing_rule="응답의 <year>총계</year> 합계 행은 관측값으로 세지 않는다",
    ),
    IndicatorDefinition(
        indicator_id="trade_balance", field_id=FIELD_EXTERNAL_TRADE, primary_source=SOURCE_CUSTOMS,
        table_code=None, item_code="balPayments", org_id=None, classification=(),
        raw_unit="미국 달러",
        transformed_unit="미국 달러", transform_rule=TRANSFORM_OFFICIAL_AMOUNT, agency="관세청",
        attribution="관세청 수출입총괄",
        license_status=LICENSE_ALLOWED,
        license_review_status=REVIEW_VERIFIED,
        commercial_use=COMMERCIAL_ALLOWED,
        redistribution_rule=_CUSTOMS_REDISTRIBUTION,
        export_notice=_CUSTOMS_EXPORT_NOTICE,
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ANNUAL_REVERIFY_BY,
        series_start="1989-01",
        revision_history_available=False,
        publication_lag_months=0,
        published_precision=0,
        release_rule=_NO_RELEASE_DATE,
        missing_rule="원천 제공값이다. 수출에서 수입을 빼 만들지 않는다",
    ),
    # --- 노동 (pd11 §2). 실업률은 원천이 계절조정한 값이라 우리가 다시 조정하지 않는다 ---
    _kosis("employed_persons", FIELD_LABOR, "DT_1DA7001S", "T30",
           (("objL1", "0"),), "천명", TRANSFORM_YOY, "1999-06", 1, 1),
    IndicatorDefinition(
        indicator_id="unemployment_rate", field_id=FIELD_LABOR, primary_source=SOURCE_KOSIS,
        table_code="DT_1DA9001S", item_code="T80", org_id=ORG_STATISTICS_KOREA,
        classification=(("objL1", "00"),), raw_unit="%", transformed_unit="%",
        transform_rule=TRANSFORM_SEASONAL_RATE, agency=_STATISTICS_KOREA,
        attribution=_KOSIS_ATTRIBUTION,
        license_status=LICENSE_ALLOWED, license_review_status=REVIEW_VERIFIED,
        commercial_use=COMMERCIAL_ALLOWED,
        redistribution_rule=_KOSIS_REDISTRIBUTION,
        export_notice=_KOSIS_EXPORT_NOTICE,
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ANNUAL_REVERIFY_BY,
        series_start="1999-06",
        revision_history_available=True,
        publication_lag_months=1,
        published_precision=1,
        release_rule=_TABLE_UPDATED_AT,
        missing_rule="원천이 계절조정한 값을 그대로 쓴다. 우리가 계절조정하지 않는다",
    ),
    # --- 물가·원자재 ---
    _kosis("consumer_price_index", FIELD_PRICES_COMMODITIES, "DT_1J22003", "T",
           (("objL1", "T10"),), "2020=100", TRANSFORM_YOY, "1980-01", 1, 2),
    IndicatorDefinition(
        indicator_id="dubai_crude", field_id=FIELD_PRICES_COMMODITIES, primary_source=SOURCE_ECOS,
        table_code="902Y003", item_code="010102", org_id=None, classification=(),
        raw_unit="달러/배럴",
        transformed_unit="달러/배럴", transform_rule=TRANSFORM_MONTH_AVERAGE,
        agency="국제통화기금(IMF)", attribution=_ECOS_ATTRIBUTION,
        license_status=LICENSE_ALLOWED,
        # 작성기관이 국제통화기금이라 한국은행 약관의 상업 이용 제한이 이 계열에
        # 걸리는지 물을 창구가 없다(pd11 §1). 제한된 것이 아니라 **모르는 것**이므로
        # RESTRICTED 가 아니라 UNKNOWN 이다 — RESTRICTED 로 적으면 내보내기에서 빠지는데
        # 그것은 확인한 사실이 아니다.
        commercial_use=COMMERCIAL_UNKNOWN,
        redistribution_rule=_ECOS_REDISTRIBUTION,
        export_notice=_ECOS_EXPORT_NOTICE
        + " 이 계열의 작성기관은 국제통화기금이며 상업적 이용 조건을 확인하지 못했다",
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ECOS_REVERIFY_BY,
        # 한국은행 약관은 상업 이용 제한 계열에 작성기관 승인을 요구하는데, 이 계열이 제한
        # 대상인지 물을 창구가 없다(pd11 §1). 조건 자체를 모르는 UNVERIFIED 와 구분해 두고
        # 쓴다 — 하나로 묶으면 발행이 보류되어 나머지 16개까지 멈춘다.
        license_review_status=REVIEW_PENDING_RIGHTS, series_start="1992-01",
        revision_history_available=False,
        publication_lag_months=1,
        published_precision=2,
        release_rule=_NO_RELEASE_DATE,
    ),
    # --- 금융·주택 ---
    IndicatorDefinition(
        indicator_id="treasury_bond_3y", field_id=FIELD_FINANCE_HOUSING,
        primary_source=SOURCE_ECOS, table_code="817Y002", item_code="010200000", org_id=None,
        classification=(), source_cycle=CYCLE_DAILY, raw_unit="연%",
        transformed_unit="연%", transform_rule=TRANSFORM_MONTH_END, agency="한국은행",
        attribution=_ECOS_ATTRIBUTION,
        license_status=LICENSE_ALLOWED, license_review_status=REVIEW_VERIFIED,
        commercial_use=COMMERCIAL_ALLOWED,
        redistribution_rule=_ECOS_REDISTRIBUTION,
        export_notice=_ECOS_EXPORT_NOTICE,
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ECOS_REVERIFY_BY,
        series_start="1998-11",
        revision_history_available=False,
        publication_lag_months=1,
        published_precision=3,
        release_rule=_NO_RELEASE_DATE,
        missing_rule="일별 계열이다. 그 달 마지막 거래일 값을 쓰고 휴장일 값을 만들지 않는다",
    ),
    IndicatorDefinition(
        # 일별 표(731Y001)는 서울외국환중개 작성이라 쓰지 않는다. 월별 표의 말일자료가
        # 한국은행 작성이면서 월 기말 규칙을 그대로 충족한다(pd11 §1).
        indicator_id="usd_krw_rate", field_id=FIELD_FINANCE_HOUSING, primary_source=SOURCE_ECOS,
        table_code="731Y004", item_code="0000001/0000200", org_id=None, classification=(),
        raw_unit="원", transformed_unit="원",
        transform_rule=TRANSFORM_MONTH_END, agency="한국은행", attribution=_ECOS_ATTRIBUTION,
        license_status=LICENSE_ALLOWED, license_review_status=REVIEW_VERIFIED,
        commercial_use=COMMERCIAL_ALLOWED,
        redistribution_rule=_ECOS_REDISTRIBUTION,
        export_notice=_ECOS_EXPORT_NOTICE,
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ECOS_REVERIFY_BY,
        series_start="1964-05",
        revision_history_available=False,
        publication_lag_months=1,
        published_precision=1,
        release_rule=_NO_RELEASE_DATE,
        missing_rule="항목2 0000200 말일자료다. 평균자료(0000100)와 섞지 않는다",
    ),
    IndicatorDefinition(
        indicator_id="housing_sale_price", field_id=FIELD_FINANCE_HOUSING,
        primary_source=SOURCE_KOSIS, table_code="DT_30404_C012", item_code="sales",
        org_id=ORG_REAL_ESTATE_BOARD, classification=(("objL1", "00"), ("objL2", "a0")),
        raw_unit="2026.6=100", transformed_unit="%", transform_rule=TRANSFORM_MOM,
        agency="한국부동산원", attribution=_KOSIS_ATTRIBUTION,
        license_status=LICENSE_ALLOWED, license_review_status=REVIEW_VERIFIED,
        commercial_use=COMMERCIAL_ALLOWED,
        redistribution_rule=_KOSIS_REDISTRIBUTION,
        export_notice=_KOSIS_EXPORT_NOTICE,
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ANNUAL_REVERIFY_BY,
        # 현행 표 하나는 2021-06 부터지만 연결 사슬을 거치면 2000-01 까지 닿는다.
        series_start="2000-01",
        revision_history_available=True,
        publication_lag_months=2,
        published_precision=8,
        release_rule=_TABLE_UPDATED_AT,
        break_link_rule=_HOUSING_BREAK_LINK_RULE,
        link_chain=HOUSING_LINK_CHAIN,
    ),
    # --- 해외 경기 (pd11 §3). 원천이 주는 증가율(TR/GY)은 지수보다 여섯 달 늦어 쓰지 않는다 ---
    IndicatorDefinition(
        indicator_id="g20_cli", field_id=FIELD_OVERSEAS, primary_source=SOURCE_OECD,
        table_code="OECD.SDD.STES,DSD_STES@DF_CLI", item_code=_G20_CLI_KEY, org_id=None,
        classification=(), raw_unit="지수", transformed_unit="%", transform_rule=TRANSFORM_YOY,
        agency="경제협력개발기구(OECD)", attribution="OECD Composite Leading Indicators",
        license_status=LICENSE_ALLOWED, license_review_status=REVIEW_VERIFIED,
        commercial_use=COMMERCIAL_ALLOWED,
        redistribution_rule=_OECD_REDISTRIBUTION,
        export_notice=_OECD_EXPORT_NOTICE,
        license_verified_on=LICENSE_VERIFIED_ON,
        license_reverify_by=ANNUAL_REVERIFY_BY,
        series_start="1961-01",
        revision_history_available=True, publication_lag_months=1, published_precision=5,
        release_rule="응답 헤더 Last-Modified 를 데이터셋 갱신일로 쓴다."
        " SDMX 헤더의 Prepared 는 응답 생성 시각이라 개정 식별자로 쓰지 않는다",
        missing_rule="증가율은 지수에서 직접 계산한다. 원천의 TR/GY 계열을 쓰지 않는다 (FR-019)",
    ),
)

EXPECTED_INDICATOR_COUNT: Final[int] = 17

# FR-004: 경제적 중요성이 같다는 주장이 아니라 Thorp 검색 모델의 명목 설계 선택이다.
NOMINAL_WEIGHT_POLICY: Final[str] = (
    "Thorp의 설계 선택: 7개 분야를 각각 1/7, 분야 내부 지표를 동일가중으로 둔다. "
    "경제적 중요성이 동일하다는 의미는 아니다."
)


def nominal_weights(
    indicators: tuple[IndicatorDefinition, ...] = INDICATORS,
) -> dict[str, Decimal]:
    """분야 간 1/7, 분야 안에서 동일가중인 명목 비중 (FR-004).

    **이것은 Thorp 의 설계 선택이지 경제적 중요성이 같다는 뜻이 아니다.**
    """
    validate(indicators)
    per_field: dict[str, list[str]] = {field_id: [] for field_id in FIELD_IDS}
    for indicator in indicators:
        per_field[indicator.field_id].append(indicator.indicator_id)
    field_weight = Decimal(1) / Decimal(len(FIELD_IDS))
    weights: dict[str, Decimal] = {}
    for members in per_field.values():
        if not members:
            continue
        share = field_weight / Decimal(len(members))
        for indicator_id in members:
            weights[indicator_id] = share
    return weights


def _month_index(period: str) -> int:
    return int(period[:4]) * 12 + int(period[5:7]) - 1


def expected_end_month(indicator: IndicatorDefinition, as_of: str) -> str:
    """``as_of`` 달 기준으로 **값이 와 있어야 할 가장 최근 대상월** (FR-011b(3)).

    ``as_of`` 는 ``YYYY-MM`` 이다. 원천이 뒤늦게 내는 만큼 뒤로 물린다.
    """
    index = _month_index(as_of) - indicator.publication_lag_months
    return f"{index // 12:04d}-{index % 12 + 1:02d}"


def expected_month_count(indicator: IndicatorDefinition, as_of: str) -> int:
    """전 구간 적재가 끝났다면 있어야 할 **대상월 수** (FR-011b(3)).

    개수를 등록표에 상수로 적지 않는 이유는 그 수가 달마다 늘기 때문이다. 상수로 적으면 다음
    달에 곧바로 틀리고, 틀린 기대값은 미완료를 정상으로 보이게 한다 — 기대가 실제보다 작으면
    무엇이 빠졌든 판정은 늘 통과한다.

    **변환값이 아니라 대상월을 센다.** 전년동월 대비의 첫 열두 달은 비교월이 없어 결측이지만
    그 달들도 받은 대상월이다. 값이 있는 달만 세면 증가율 지표가 영영 미완료로 남는다.
    """
    start, end = indicator.series_start[:7], expected_end_month(indicator, as_of)
    return max(_month_index(end) - _month_index(start) + 1, 0)


def validate(indicators: tuple[IndicatorDefinition, ...] = INDICATORS) -> None:
    """등록표 단위의 불변식을 본다. 행 단위 제약으로는 표현할 수 없는 것들이다.

    DB 의 ``CHECK`` 는 한 행만 보므로 "17개"와 "7개 분야"와 "주 공급원 하나"를 잡지 못한다.
    """
    if len(indicators) != EXPECTED_INDICATOR_COUNT:
        raise RegistryError(f"지표는 {EXPECTED_INDICATOR_COUNT}개여야 한다: {len(indicators)}개")

    seen: set[str] = set()
    for indicator in indicators:
        if indicator.indicator_id in seen:
            raise RegistryError(f"지표 id 가 겹친다: {indicator.indicator_id}")
        seen.add(indicator.indicator_id)
        if indicator.field_id not in FIELD_IDS:
            raise RegistryError(
                f"{indicator.indicator_id}의 분야가 7개 밖이다: {indicator.field_id}"
            )
        if indicator.refresh_months_n < 1:
            raise RegistryError(f"{indicator.indicator_id}의 재조회 구간이 1보다 작다")
        if indicator.source_cycle not in CYCLE_IDS:
            raise RegistryError(
                f"{indicator.indicator_id}의 주기가 {', '.join(CYCLE_IDS)} 밖이다:"
                f" {indicator.source_cycle}"
            )
        if indicator.publication_lag_months < 0:
            raise RegistryError(
                f"{indicator.indicator_id}의 공표 지연이 음수다:"
                f" {indicator.publication_lag_months}. 원천이 미래를 낼 수는 없다"
            )
        if indicator.license_status not in LICENSE_STATUSES:
            raise RegistryError(
                f"{indicator.indicator_id}의 이용 허락 상태가 {', '.join(LICENSE_STATUSES)}"
                f" 밖이다: {indicator.license_status}"
            )
        if indicator.commercial_use not in COMMERCIAL_USE_STATES:
            raise RegistryError(
                f"{indicator.indicator_id}의 상업 이용 상태가"
                f" {', '.join(COMMERCIAL_USE_STATES)} 밖이다: {indicator.commercial_use}"
            )
        # 중단은 사유와 시점이 함께 남아야 한다(FR-016b). 왜 빠졌는지 없이 지표만 사라지면
        # 나중에 되돌릴 근거가 없다. 스키마의 ck_macro_indicator_suspension 과 같은 규칙이다.
        if indicator.license_status == LICENSE_SUSPENDED and not (
            indicator.license_suspended_on and indicator.license_suspended_reason
        ):
            raise RegistryError(
                f"{indicator.indicator_id}가 중단인데 사유나 시점이 없다."
                " 왜 빠졌는지 없이 지표만 사라지면 되돌릴 근거가 없다"
            )
        if indicator.license_status != LICENSE_SUSPENDED and (
            indicator.license_suspended_on or indicator.license_suspended_reason
        ):
            raise RegistryError(
                f"{indicator.indicator_id}가 중단이 아닌데 중단 기록이 남아 있다"
            )
        # 상업 이용이 제한된 계열은 어느 소비 경로로도 내보내지 않는다(FR-018). 내보낼 수
        # 없는 것을 수집·발행만 계속하면 쓰지 못할 자료가 쌓인다.
        if (
            indicator.commercial_use == COMMERCIAL_RESTRICTED
            and indicator.license_status == LICENSE_ALLOWED
        ):
            raise RegistryError(
                f"{indicator.indicator_id}는 상업 이용이 제한됐는데 이용 허락이 ALLOWED 다."
                " 제한된 계열은 어느 경로로도 내보내지 않으므로 허락 상태를 함께 낮춘다"
            )
        if indicator.primary_source not in SOURCE_POLICIES:
            raise RegistryError(
                f"{indicator.indicator_id}의 원천 {indicator.primary_source!r} 에 호출 규칙이"
                " 없다. 한도를 모르는 원천은 정기 수집에 올리지 않는다 (FR-017a)"
            )
        # 월 평균은 그 달 거래일이 다 모였는지로 값을 낼지 정하는데, 달력이 없으면 그 판정을
        # 할 수 없어 매달 결측이 된다(FR-006b). 지금 여기 걸리는 지표는 없다 — 걸리는 지표를
        # 나중에 더할 때 조용히 빈 칸으로 들어오지 않게 막는 자리다.
        if (
            indicator.transform_rule == TRANSFORM_MONTH_AVERAGE
            and indicator.source_cycle == CYCLE_DAILY
            and not indicator.trading_calendar
        ):
            raise RegistryError(
                f"{indicator.indicator_id}: 일별 계열의 월 평균은 거래일 달력이 있어야 한다"
                " (FR-006b). 달력 없이는 그 달이 다 모였는지 판정할 수 없어 매달 결측이 된다."
                f" {TRADING_CALENDAR_UNVERIFIED}"
            )

    used_fields = {indicator.field_id for indicator in indicators}
    missing = set(FIELD_IDS) - used_fields
    if missing:
        raise RegistryError(f"지표가 하나도 없는 분야가 있다: {sorted(missing)}")


def used_sources(
    indicators: tuple[IndicatorDefinition, ...] = INDICATORS,
) -> tuple[str, ...]:
    """등록표가 실제로 쓰는 원천. 설정에 있는 넷 전부가 아니라 **쓰이는 것만** 돌려준다.

    수집 진입점이 이 목록으로 설정을 읽는다. `config.SOURCE_IDS` 를 그대로 쓰면 등록표에서
    빠진 원천의 접속 설정까지 요구하게 되고, 그 원천을 안 쓰기로 한 결정이 코드에서 지워진다.
    """
    seen: list[str] = []
    for indicator in indicators:
        if indicator.primary_source not in seen:
            seen.append(indicator.primary_source)
    return tuple(seen)


def by_field() -> dict[str, tuple[IndicatorDefinition, ...]]:
    """분야별 지표. 표시 순서는 `INDICATORS` 의 순서를 따른다."""
    grouped: dict[str, list[IndicatorDefinition]] = {field_id: [] for field_id in FIELD_IDS}
    for indicator in INDICATORS:
        grouped[indicator.field_id].append(indicator)
    return {field_id: tuple(members) for field_id, members in grouped.items()}


def collectible(indicators: Sequence[IndicatorDefinition] = INDICATORS) -> tuple[
    IndicatorDefinition, ...
]:
    """새로 수집하고 새 자료 버전에 넣을 수 있는 지표만 (FR-016b).

    중단된 지표는 빠진다. **이미 발행한 버전에서 지우지는 않는다** — 지우면 그 버전을 참조한
    과거 검색 결과의 재현이 깨진다(원칙 VIII). 중단은 "앞으로 안 쓴다"이지 "없던 일로 한다"가
    아니다.
    """
    return tuple(
        indicator
        for indicator in indicators
        if indicator.license_status in COLLECTIBLE_LICENSE_STATUSES
    )


def suspended(indicators: Sequence[IndicatorDefinition] = INDICATORS) -> tuple[
    IndicatorDefinition, ...
]:
    """이용이 중단된 지표. 조회 응답이 "빠진 상태임"을 드러내는 데 쓴다 (FR-016b, 원칙 VI)."""
    return tuple(
        indicator
        for indicator in indicators
        if indicator.license_status == LICENSE_SUSPENDED
    )


def suspend(
    indicator: IndicatorDefinition, *, reason: str, on: date
) -> IndicatorDefinition:
    """지표 하나를 중단 상태로 바꾼 사본을 낸다 (FR-016b).

    사유를 **인자로 받아야만** 만들어진다. 기본값을 두면 사유 없는 중단이 생기고, 그러면
    나중에 왜 빠졌는지 답할 근거가 사라진다.
    """
    if not reason.strip():
        raise RegistryError(f"{indicator.indicator_id} 중단에 사유가 없다")
    return replace(
        indicator,
        license_status=LICENSE_SUSPENDED,
        license_suspended_on=on,
        license_suspended_reason=reason,
    )


def reverification_due(
    indicators: Sequence[IndicatorDefinition] = INDICATORS, *, on: date
) -> tuple[IndicatorDefinition, ...]:
    """``on`` 기준으로 이용 조건·인증 자격을 다시 확인해야 하는 지표 (FR-018a).

    **지났다고 수집을 막지 않는다.** 막으면 확인이 하루 늦은 것이 자료를 끊는 일이 되어,
    확인하라는 신호가 자료를 끊는 벌이 된다. 드러내는 데까지가 이 함수의 일이고, 확인해 보니
    조건이 달라졌으면 그때 :func:`suspend` 로 넘긴다.

    시한을 적지 않은 지표(``license_reverify_by`` 가 ``None``)는 **확인할 시점을 정하지 못한
    것**이므로 늘 대상이다. 시한 없음을 "확인 안 해도 됨"으로 읽으면 그 지표만 영영 빠진다.
    """
    return tuple(
        indicator
        for indicator in indicators
        if indicator.license_reverify_by is None or indicator.license_reverify_by <= on
    )
