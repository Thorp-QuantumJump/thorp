"""수집 실행 — 어댑터·변환·정규화·발행을 한 줄로 잇는다 (spec 008 FR-017, tasks T081).

여기까지 오기 전에는 조각이 다 있어도 **한 번도 연속으로 돌아본 적이 없었다.** 어댑터는 경로를
만들고 응답을 읽을 줄 알고, 변환기는 월 값을 만들 줄 알고, 발행기는 스냅샷을 쓸 줄 아는데, 그것들을
차례로 부르는 코드가 없었다.

세 가지를 함께 한다.

- **원본을 먼저 남긴다**(T080, FR-009). 받은 본문을 그대로 파일로 쓰고 해시를 `macro_raw_revision`
  에 쌓는다. 값이 달라졌을 때 원천이 개정한 것인지 우리 변환이 바뀐 것인지는 원본이 있어야 갈린다.
  같은 응답을 다시 받으면 해시가 같아 이력이 늘지 않는다.
- **지표마다 결과를 남긴다**(T082, FR-011). 성공·실패·한도 도달·키 미발급을 구분해
  `macro_collection_item_status` 에 쓴다. 한 덩어리로 묶으면 사람이 할 다음 행동이
  응답에서 사라진다.
- **하나라도 못 받으면 발행하지 않는다**(FR-011). 판정은 :mod:`~data_pipeline.macro.publish` 가
  하고 여기서는 그 결과를 그대로 따른다.

**네트워크는 :class:`~data_pipeline.macro.fetch.Fetcher` 뒤에 있다.** 저장된 표본을 읽는 대역을
끼우면 인증키 없이 이 경로 전체가 돈다(FR-022).
"""

from __future__ import annotations

import json
import logging
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Final

from sqlalchemy import text
from sqlalchemy.engine import Engine

from data_pipeline.macro import fetch as transport
from data_pipeline.macro import normalize as normalizer
from data_pipeline.macro import publish, registry, storage
from data_pipeline.macro.config import (
    SOURCE_CUSTOMS,
    SOURCE_ECOS,
    SOURCE_KOSIS,
    SOURCE_OECD,
    MacroConfigError,
    SourceConfig,
    load_source_config,
)
from data_pipeline.macro.normalize import NormalizedObservation
from data_pipeline.macro.registry import IndicatorDefinition
from data_pipeline.macro.sources import customs, ecos, housing, kosis, oecd
from data_pipeline.macro.sources.base import (
    MAX_MONTHS_CUSTOMS,
    PermanentSourceError,
    QuotaExhaustedError,
    SourceError,
    SourceObservation,
    split_months,
)

logger = logging.getLogger(__name__)

#: `macro_collection_item_status` 가 받는 값. 사람이 할 다음 행동이 저마다 다르다.
STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_FAILED = "FAILED"
STATUS_QUOTA = "QUOTA_EXHAUSTED"
STATUS_KEY_NOT_ISSUED = "KEY_NOT_ISSUED"
STATUS_RIGHTS_PENDING = "RIGHTS_PENDING"
STATUS_RIGHTS_UNVERIFIED = "RIGHTS_UNVERIFIED"

#: 경제협력개발기구 SDMX 에 JSON 을 요청하는 헤더.
#:
#: **판본을 2.0 으로 못 박는다.** 1.0 은 최상위에 ``structure`` 하나를 두고 2.0 은
#: ``structures`` 배열을 둔다. 어댑터는 2.0 모양을 읽으므로 판본을 비워 두거나 1.0 을 먼저
#: 적으면 원천이 1.0 을 주고 "응답에 structures 가 없다"로 끝난다 — 리허설에서 그렇게 걸렸다.
_SDMX_JSON_HEADERS: Final[dict[str, str]] = {
    "Accept": "application/vnd.sdmx.data+json;version=2.0.0"
}


@dataclass(frozen=True)
class RawDocument:
    """받은 그대로의 응답 하나. 파일로 남고 해시로 개정을 가린다."""

    indicator_id: str
    source: str
    body: str
    received_at: datetime
    path: Path
    sha256: str


@dataclass
class IndicatorOutcome:
    """지표 하나의 수집 결과. 성공이든 아니든 반드시 하나씩 남는다."""

    indicator_id: str
    source: str
    status: str
    observations: list[SourceObservation] = field(default_factory=list)
    raw_documents: list[RawDocument] = field(default_factory=list)
    error_class: str | None = None
    #: 응답에서 관측값이 아니어서 뺀 행의 수 (FR-017a). 관세청의 ``<year>총계</year>`` 가 여기다.
    excluded_rows: int = 0
    #: 달마다 그 값을 준 구간. 개편으로 나뉜 계열만 채워진다 (FR-010).
    break_segments: dict[str, str] = field(default_factory=dict)
    #: 그중 등록표의 연결 규칙으로 이어진 구간들. 이어지지 않은 구간을 넘는 비교는 결측이다.
    linked_segments: frozenset[str] = frozenset()

    @property
    def succeeded(self) -> bool:
        return self.status == STATUS_SUCCEEDED


def _window(indicator: IndicatorDefinition, mode: str, today: date) -> tuple[str, str]:
    """받을 구간. 최초는 전 구간, 정기는 최근 N개월이다 (FR-017).

    뒤쪽 구간을 함께 받는 이유는 잠정치가 확정치로 개정된 것을 잡기 위해서다. 새 발표분만
    받으면 정정을 놓친다.
    """
    end = f"{today.year:04d}-{today.month:02d}"
    if mode == "initial":
        return indicator.series_start or "2000-01", end
    # 증가율은 갱신 대상월만 다시 받아서는 계산할 수 없다. 정기 수집 창 앞에
    # 변환식의 분모가 놓이는 선행 구간을 함께 열어, 새 관측이 직전의 정상 값을
    # ``NOT_TRANSFORMABLE`` 로 덮지 않게 한다.
    transform_lookback = {
        registry.TRANSFORM_YOY: 12,
        registry.TRANSFORM_MOM: 1,
    }.get(indicator.transform_rule, 0)
    months_back = max(indicator.refresh_months_n, 1) + transform_lookback
    year = today.year
    month = today.month - months_back
    while month <= 0:
        month += 12
        year -= 1
    return f"{year:04d}-{month:02d}", end


def _compact(period: str) -> str:
    """``YYYY-MM`` 을 원천이 받는 ``YYYYMM`` 으로."""
    return period.replace("-", "")


#: 한 달의 마지막 날. 일별 조회의 끝을 그 달 말일로 잡는다.
_MONTH_END_DAY: Final[dict[int, int]] = {1: 31, 3: 31, 5: 31, 7: 31, 8: 31, 10: 31, 12: 31}


def _ecos_period(period: str, cycle: str, *, end: bool) -> str:
    """한국은행이 받는 조회 구간 표기. **주기마다 자릿수가 다르다**.

    일별 계열에 ``YYYYMM`` 을 주면 ``[ERROR-101] 주기와 다른 형식의 날짜 형식입니다`` 가
    돌아온다. 국고채가 유일한 일별 계열인데, 그 사실은 등록표의 ``source_cycle`` 이 들고
    있으므로 여기서 통계표 코드로 추측하지 않는다(FR-005).

    끝 달은 말일까지 연다. 그 달의 며칠까지 자료가 있는지는 원천이 알고, 우리가 좁히면
    그달 마지막 거래일을 놓쳐 월 기말이 한 달 밀린다(FR-006a).
    """
    compact = _compact(period)
    if cycle != "D":
        return compact
    year, month = int(period[:4]), int(period[5:7])
    if not end:
        return f"{compact}01"
    if month == 2:
        last = 29 if (year % 4 == 0 and year % 100 != 0) or year % 400 == 0 else 28
    else:
        last = _MONTH_END_DAY.get(month, 30)
    return f"{compact}{last:02d}"


def _save_raw(
    outcome: IndicatorOutcome,
    data_root: Path,
    source_id: str,
    indicator_id: str,
    body: str,
    received_at: datetime,
) -> None:
    """원본을 파일로 남기고 결과에 붙인다 (T080).

    확장자는 본문 모양을 따른다 — 관세청만 XML 이고 나머지는 JSON 이다. 되읽을 때 무엇으로
    파싱할지 파일 이름이 말해 준다.
    """
    suffix = ".xml" if body.lstrip().startswith("<") else ".json"
    path = storage.raw_path(data_root, source_id, indicator_id, received_at, suffix)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    outcome.raw_documents.append(
        RawDocument(
            indicator_id=indicator_id,
            source=source_id,
            body=body,
            received_at=received_at,
            path=path,
            sha256=storage.content_hash(body.encode("utf-8")),
        )
    )


def _cached_raw(data_root: Path, source_id: str, indicator_id: str) -> RawDocument | None:
    """중단된 분할 수집이 이미 봉인한 원본을 다시 내려받지 않게 되읽는다."""
    directory = data_root / storage.RAW_ROOT / source_id / indicator_id
    candidates = (
        sorted(
            (
                path for path in directory.glob("*/*")
                if path.suffix in {".json", ".xml"}
                and path.with_suffix(path.suffix + ".complete").exists()
            ),
            reverse=True,
        )
        if directory.exists()
        else []
    )
    if not candidates:
        return None
    path = candidates[0]
    body = path.read_text(encoding="utf-8")
    received_at = datetime.fromtimestamp(path.stat().st_mtime, tz=UTC)
    return RawDocument(
        indicator_id=indicator_id,
        source=source_id,
        body=body,
        received_at=received_at,
        path=path,
        sha256=storage.content_hash(body.encode("utf-8")),
    )


def _mark_raw_complete(document: RawDocument) -> None:
    """파싱까지 성공한 분할 원본만 재개 체크포인트로 표시한다."""
    marker = document.path.with_suffix(document.path.suffix + ".complete")
    marker.write_text(document.sha256, encoding="ascii")


def _classify(error: Exception) -> tuple[str, str]:
    """실패를 사람이 다음에 할 행동으로 가른다. 한 덩어리로 묶지 않는다(FR-011).

    **원인을 풀어 본다.** 재시도를 다 쓰면 `fetch_with_retry` 가 마지막 오류를 `SourceError`
    로 감싸는데, 그대로 적으면 기록에 남는 것이 전부 ``SourceError`` 가 되어 무엇 때문에
    실패했는지 알 수 없다. 사람이 로그를 뒤지지 않고 상태만 보고 다음 행동을 정할 수 있어야
    이 열이 쓸모가 있다.
    """
    cause = error.__cause__ if type(error) is SourceError and error.__cause__ else error
    if isinstance(cause, MacroConfigError):
        return STATUS_KEY_NOT_ISSUED, type(cause).__name__
    if isinstance(cause, QuotaExhaustedError):
        return STATUS_QUOTA, type(cause).__name__
    return STATUS_FAILED, type(cause).__name__


def collect(
    indicators: Sequence[IndicatorDefinition],
    *,
    fetcher: transport.Fetcher,
    data_root: Path,
    mode: str = "initial",
    today: date | None = None,
    configs: Mapping[str, SourceConfig] | None = None,
) -> list[IndicatorOutcome]:
    """17개를 원천별로 받아 지표마다 결과를 하나씩 돌려준다.

    **한 지표가 실패해도 나머지를 계속 받는다.** 첫 실패에서 멈추면 무엇이 더 실패했는지 알 수
    없고, 다음 실행에서 같은 자리에서 또 멈춘다.
    """
    today = today or datetime.now(UTC).date()
    resolved = dict(configs or {})
    outcomes: list[IndicatorOutcome] = []

    by_source: dict[str, list[IndicatorDefinition]] = {}
    for indicator in indicators:
        by_source.setdefault(indicator.primary_source, []).append(indicator)

    for source_id, members in by_source.items():
        config = resolved.get(source_id) or load_source_config(source_id)
        if source_id == SOURCE_CUSTOMS:
            outcomes.extend(
                _collect_customs(members, fetcher, config, data_root, mode, today)
            )
            continue
        quota_reached = False
        for indicator in members:
            if quota_reached:
                # 한도는 원천 단위다. 앞 지표가 거절당했으면 뒤 지표도 같은 답을 받는다 —
                # 부르지 않고 그 사실을 상태로 남긴다. 무엇을 못 받았는지는 기록에 남아야 한다.
                outcomes.append(
                    IndicatorOutcome(
                        indicator_id=indicator.indicator_id,
                        source=source_id,
                        status=STATUS_QUOTA,
                        error_class="QuotaExhaustedError",
                    )
                )
                continue
            outcome = _collect_one(indicator, fetcher, config, data_root, mode, today)
            outcomes.append(outcome)
            quota_reached = outcome.status == STATUS_QUOTA
    return outcomes


def _collect_one(
    indicator: IndicatorDefinition,
    fetcher: transport.Fetcher,
    config: SourceConfig,
    data_root: Path,
    mode: str,
    today: date,
) -> IndicatorOutcome:
    outcome = IndicatorOutcome(
        indicator_id=indicator.indicator_id,
        source=indicator.primary_source,
        status=STATUS_SUCCEEDED,
    )
    start, end = _window(indicator, mode, today)
    received_at = datetime.now(UTC)
    try:
        if indicator.link_chain:
            outcome.observations = _collect_linked(
                indicator, fetcher, config, data_root, start, end, received_at, outcome
            )
        elif indicator.primary_source == SOURCE_ECOS:
            cycle = ecos.cycle_for(indicator)
            path = ecos.build_path(
                indicator,
                _ecos_period(start, cycle, end=False),
                _ecos_period(end, cycle, end=True),
            )
            body = transport.fetch_text(fetcher, config, path)
            _save_raw(
                outcome, data_root, config.source_id, indicator.indicator_id, body, received_at
            )
            outcome.observations = ecos.parse(indicator, _as_json(body, config))
        elif indicator.primary_source == SOURCE_KOSIS:
            params = kosis.build_params(indicator, _compact(start), _compact(end))
            body = transport.fetch_text(fetcher, config, "", params, key_param="apiKey")
            _save_raw(
                outcome, data_root, config.source_id, indicator.indicator_id, body, received_at
            )
            outcome.observations = kosis.parse(indicator, _as_json(body, config))
        elif indicator.primary_source == SOURCE_OECD:
            path = oecd.build_path(indicator)
            # SDMX 는 **기본이 XML 이다.** 달라고 하지 않으면 JSON 을 주지 않는다 — 그대로
            # 파서에 넣으면 "응답이 JSON 이 아니다"로 끝난다. 형식을 질의 인자가 아니라
            # 헤더로 고르는 이유는 그것이 SDMX 규약이고, 인자는 판마다 이름이 달라서다.
            body = transport.fetch_text(
                fetcher, config, path, needs_key=False, headers=_SDMX_JSON_HEADERS
            )
            _save_raw(
                outcome, data_root, config.source_id, indicator.indicator_id, body, received_at
            )
            outcome.observations = oecd.parse(indicator, _as_json(body, config))
        else:
            raise PermanentSourceError(
                f"{indicator.indicator_id}: 모르는 원천 {indicator.primary_source!r}"
            )
    except (SourceError, MacroConfigError) as error:
        outcome.status, outcome.error_class = _classify(error)
        logger.warning("%s 수집 실패: %s", indicator.indicator_id, error)
    return outcome


def _collect_linked(
    indicator: IndicatorDefinition,
    fetcher: transport.Fetcher,
    config: SourceConfig,
    data_root: Path,
    start: str,
    end: str,
    received_at: datetime,
    outcome: IndicatorOutcome,
) -> list[SourceObservation]:
    """개편으로 나뉜 계열. 사슬의 표를 모두 받아 겹침 구간 비율로 잇는다 (FR-010).

    **현행 표만 받고 끝내지 않는다.** 그렇게 하면 응답은 정상이고 값도 멀쩡한데 확보 구간만
    조용히 짧아진다 — 주택가격은 2000-01 이 아니라 2021-06 부터만 온다.
    """
    values_by_base_month: dict[str, dict[str, object]] = {}
    for table in indicator.link_chain:
        params = housing.build_params(table, _compact(table.coverage_start), _compact(end))
        body = transport.fetch_text(fetcher, config, "", params, key_param="apiKey")
        _save_raw(
            outcome,
            data_root,
            config.source_id,
            f"{indicator.indicator_id}__{table.base_month.replace('-', '')}",
            body,
            received_at,
        )
        values_by_base_month[table.base_month] = housing.parse_table(
            table, _as_json(body, config)
        )

    segments = housing.segments_from(values_by_base_month, indicator.link_chain)
    linked = housing.link(segments)
    # 어느 표가 어느 달을 줬는지 변환까지 들고 간다. 증가율이 이음매를 넘을 때 그 둘이
    # 실제로 이어진 구간인지 확인해야 하기 때문이다(FR-006).
    outcome.break_segments = {
        point.period: point.source_base_month for point in linked.points
    }
    outcome.linked_segments = frozenset(
        {segments[0].base_month} | {link.from_base_month for link in linked.links}
    )
    return [
        SourceObservation(
            indicator_id=indicator.indicator_id,
            period=point.period,
            value=point.value,
            unit=indicator.raw_unit,
        )
        for point in linked.points
        if point.period >= start
    ]


def _collect_customs(
    indicators: Sequence[IndicatorDefinition],
    fetcher: transport.Fetcher,
    config: SourceConfig,
    data_root: Path,
    mode: str,
    today: date,
) -> list[IndicatorOutcome]:
    """세 지표가 한 응답에서 나온다. 요청은 조회 기간 1년 제한 때문에 나눠 건다 (FR-017a)."""
    members = tuple(indicators)
    outcomes = {
        indicator.indicator_id: IndicatorOutcome(
            indicator_id=indicator.indicator_id,
            source=SOURCE_CUSTOMS,
            status=STATUS_SUCCEEDED,
        )
        for indicator in members
    }
    start, end = _window(members[0], mode, today)
    windows = split_months(_first_day(start), _first_day(end), MAX_MONTHS_CUSTOMS)
    received_at = datetime.now(UTC)

    for window in windows:
        try:
            params = customs.build_params(
                window.start.strftime("%Y%m"), window.end.strftime("%Y%m")
            )
            label = f"trade__{window.start:%Y%m}_{window.end:%Y%m}"
            first = outcomes[members[0].indicator_id]
            cached = _cached_raw(data_root, SOURCE_CUSTOMS, label)
            if cached is None:
                body = transport.fetch_text(
                    fetcher, config, "", params, key_param="serviceKey"
                )
                _save_raw(first, data_root, SOURCE_CUSTOMS, label, body, received_at)
            else:
                body = cached.body
                first.raw_documents.append(cached)
            response = customs.parse_all(body, members)
            _mark_raw_complete(first.raw_documents[-1])
            for indicator in members:
                outcomes[indicator.indicator_id].observations.extend(
                    response[indicator.indicator_id]
                )
            first.excluded_rows += response.excluded_total_rows
        except (SourceError, MacroConfigError) as error:
            status, error_class = _classify(error)
            # 한 구간이 실패하면 세 지표 모두 그 사실을 안고 간다. 받은 구간까지는 남긴다.
            for indicator in members:
                outcomes[indicator.indicator_id].status = status
                outcomes[indicator.indicator_id].error_class = error_class
            logger.warning("관세청 %s~%s 수집 실패: %s", window.start, window.end, error)
            # 실패한 구간 뒤를 계속 받지 않는다. 한도라면 추가 거절을 막고, 구조 변경이라면
            # 잘못 해석할 수 있는 같은 형식의 응답을 더 저장하지 않는다. 성공한 앞 구간은
            # 체크포인트로 남아 다음 실행이 실패한 구간부터 이어받는다(T047·T051).
            break
    return list(outcomes.values())


def _first_day(period: str) -> date:
    year, month = period.split("-")
    return date(int(year), int(month), 1)


def _as_json(body: str, config: SourceConfig) -> object:
    import json

    try:
        return json.loads(body)
    except json.JSONDecodeError as error:
        raise PermanentSourceError(
            f"{config.source_id}: 응답이 JSON 이 아니다 ({body[:120]!r})"
        ) from error


def normalize_outcomes(
    indicators: Sequence[IndicatorDefinition],
    outcomes: Sequence[IndicatorOutcome],
    *,
    as_of: date | None = None,
    trading_days: Mapping[str, Sequence[str]] | None = None,
    received_at: datetime | None = None,
) -> dict[str, list[NormalizedObservation]]:
    """받은 관측을 지표별 변환값으로 바꾼다. 못 받은 지표는 빈 목록이다."""
    by_id = {indicator.indicator_id: indicator for indicator in indicators}
    result: dict[str, list[NormalizedObservation]] = {}
    for outcome in outcomes:
        indicator = by_id.get(outcome.indicator_id)
        if indicator is None or not outcome.observations:
            result[outcome.indicator_id] = []
            continue
        result[outcome.indicator_id] = normalizer.normalize(
            indicator,
            outcome.observations,
            as_of=as_of,
            trading_days=trading_days,
            received_at=received_at,
            break_segments=outcome.break_segments or None,
            linked_segments=outcome.linked_segments,
        )
    return result


def write_processed(
    observations: Mapping[str, Sequence[NormalizedObservation]], data_root: Path
) -> list[Path]:
    """정규화한 값을 지표/연도 파티션 Parquet 로 쓴다 (data/CLAUDE.md 구조 규칙).

    적재는 ``raw/ → processed/ → PostgreSQL`` 한 방향이고, 발행기는 이 파일 목록을 봉인해
    매니페스트를 만든다. 파일이 없으면 발행이 거절된다 — 스냅샷이 참조할 실체가 없기 때문이다.

    **decimal 은 문자열로 적는다.** 부동소수로 쓰면 주택가격 지수의 끝자리가 흔들리고, 같은
    자료로 다시 돌려도 파일 해시가 달라져 재현성 판정이 무너진다(SC-004).

    빠진 달도 행으로 남긴다. 값 자리는 비우고 사유를 함께 적는다 — 파일에서 지우면 왜 없는지
    되짚을 곳이 사라진다(원칙 VI).
    """
    import pyarrow as pa
    import pyarrow.parquet as pq

    written: list[Path] = []
    for indicator_id, values in sorted(observations.items()):
        by_year: dict[int, list[NormalizedObservation]] = {}
        for value in values:
            by_year.setdefault(int(value.period[:4]), []).append(value)
        for year, rows in sorted(by_year.items()):
            table = pa.table(
                {
                    "indicator_id": [row.indicator_id for row in rows],
                    "target_month": [row.period for row in rows],
                    "transformed_value": [
                        None if row.transformed_value is None else str(row.transformed_value)
                        for row in rows
                    ],
                    "missing_reason": [row.missing_reason for row in rows],
                    "transform_rule": [row.transform_rule for row in rows],
                    "raw_unit": [row.raw_unit for row in rows],
                    "transformed_unit": [row.transformed_unit for row in rows],
                    "source_updated_at": [row.source_updated_at for row in rows],
                    "released_at": [row.released_at for row in rows],
                    "received_at": [row.received_at for row in rows],
                }
            )
            path = storage.processed_path(data_root, indicator_id, year)
            path.parent.mkdir(parents=True, exist_ok=True)
            pq.write_table(table, path)
            written.append(path)
    return written


def merge_published_observations(
    engine: Engine,
    dataset_id: int,
    indicators: Sequence[IndicatorDefinition],
    fresh: Mapping[str, Sequence[NormalizedObservation]],
) -> dict[str, list[NormalizedObservation]]:
    """정기 재조회분을 직전 불변 스냅샷에 월 단위로 덮어 새 전체 후보를 만든다.

    기존 자료 버전은 수정하지 않는다. 같은 월에 새 관측이 왔을 때만 새 후보의 그 월을 바꾸고,
    재조회 범위 밖의 과거 월은 그대로 복사한다(FR-009).
    """
    definitions = {indicator.indicator_id: indicator for indicator in indicators}
    merged: dict[str, dict[str, NormalizedObservation]] = {
        indicator_id: {} for indicator_id in definitions
    }
    with engine.begin() as connection:
        rows = connection.execute(
            text(
                "SELECT indicator_id, target_month, raw_value, transformed_value,"
                " released_at, received_at, break_segment, missing_reason"
                " FROM macro_observation WHERE dataset_version_id = :dataset"
            ),
            {"dataset": dataset_id},
        ).mappings().all()
    for row in rows:
        indicator = definitions.get(row["indicator_id"])
        if indicator is None:
            raise ValueError("기존 자료 버전에 현재 등록표 밖의 지표가 있다")
        raw_value = row["raw_value"]
        if isinstance(raw_value, str):
            raw_value = json.loads(raw_value)
        transformed = row["transformed_value"]
        if isinstance(transformed, str):
            transformed = json.loads(transformed)
        period = row["target_month"].strftime("%Y-%m")
        merged[indicator.indicator_id][period] = NormalizedObservation(
            indicator_id=indicator.indicator_id,
            period=period,
            transformed_value=None if transformed is None else Decimal(str(transformed)),
            missing_reason=row["missing_reason"],
            raw_values=tuple(
                (raw_period, None if value is None else Decimal(str(value)))
                for raw_period, value in (raw_value or [])
            ),
            transform_rule=indicator.transform_rule,
            raw_unit=indicator.raw_unit,
            transformed_unit=indicator.transformed_unit,
            source_updated_at=None,
            released_at=row["released_at"],
            received_at=row["received_at"],
            break_segment=row["break_segment"],
        )
    for indicator_id, observations in fresh.items():
        if indicator_id not in merged:
            raise ValueError(f"새 관측에 등록표 밖의 지표가 있다: {indicator_id}")
        for observation in observations:
            merged[indicator_id][observation.period] = observation
    return {
        indicator_id: [by_month[month] for month in sorted(by_month)]
        for indicator_id, by_month in merged.items()
    }


def record_raw_revisions(engine: Engine, outcomes: Sequence[IndicatorOutcome]) -> int:
    """원본 이력을 append-only 로 쌓는다 (T080, FR-009).

    **같은 응답을 다시 받으면 행을 늘리지 않는다.** 정기 재조회는 바뀐 것이 없는 달도 함께
    받으므로, 해시로 거르지 않으면 돌릴 때마다 개정이 일어난 것처럼 쌓인다.
    """
    written = 0
    with engine.begin() as connection:
        for outcome in outcomes:
            for document in outcome.raw_documents:
                existing = connection.execute(
                    text(
                        "SELECT 1 FROM macro_raw_revision"
                        " WHERE indicator_id = :indicator AND raw_hash = :hash LIMIT 1"
                    ),
                    {"indicator": document.indicator_id, "hash": document.sha256},
                ).first()
                if existing is not None:
                    continue
                next_revision = connection.execute(
                    text(
                        "SELECT COALESCE(MAX(observed_revision), 0) + 1"
                        " FROM macro_raw_revision WHERE indicator_id = :indicator"
                        "   AND target_month = :month"
                    ),
                    {"indicator": document.indicator_id, "month": _received_month(document)},
                ).scalar_one()
                connection.execute(
                    text(
                        "INSERT INTO macro_raw_revision"
                        " (indicator_id, source, target_month, raw_unit, received_at,"
                        "  observed_revision, raw_path, raw_hash)"
                        " VALUES (:indicator, :source, :month, :unit, :received,"
                        "  :revision, :path, :hash)"
                    ),
                    {
                        "indicator": document.indicator_id,
                        "source": document.source,
                        "month": _received_month(document),
                        "unit": "raw",
                        "received": document.received_at,
                        "revision": next_revision,
                        "path": str(document.path),
                        "hash": document.sha256,
                    },
                )
                written += 1
    return written


def _received_month(document: RawDocument) -> date:
    """원본 한 건이 대표하는 달. 응답이 여러 달을 담으므로 수신한 달로 적는다.

    관측치별 대상월은 `macro_observation` 이 들고, 여기 있는 것은 "이 응답을 언제 받았는가"다.
    """
    return date(document.received_at.year, document.received_at.month, 1)


def record_item_status(
    engine: Engine,
    attempt_key: str,
    outcomes: Sequence[IndicatorOutcome],
    *,
    mode: str = "REGULAR",
    held: str | None = None,
    crashed: bool = False,
) -> None:
    """지표별 수집 결과와 **발행 보류 여부**를 남긴다 (T082·T083, FR-011).

    008 조회가 이 표에서 실패 지표 목록을 읽는다. 쓰지 않으면 화면은 늘 "실패 없음"으로 보인다.

    ``held`` 가 있으면 시도 행을 ``HELD`` 와 그 사유로 남긴다. **지표가 다 성공해도 그렇다** —
    전 구간이 기대 대상월 수에 못 미쳐 막은 실행은 지표 열일곱이 모두 성공한 것이 사실이지만
    아무것도 발행되지 않았고, 그 둘을 같은 ``SUCCEEDED`` 로 적으면 DB 만 보는 사람은 구분하지
    못한다. 보류 사유가 로그에만 있으면 로그가 지나간 뒤에는 아무 데도 없다.

    ``crashed`` 는 **보류와 다른 것**이다. 보류는 우리가 규칙대로 막은 것이고 터진 것은 우리가
    죽은 것이라, 다음에 할 일이 다르다 — 앞은 모자란 자료를 채우면 되고 뒤는 원인을 찾아야
    한다. 발행기가 시도 행을 이미 ``FAILED`` 로 적었으면 그대로 두고, 행을 만들기 전에 터져
    아직 없으면 여기서 ``FAILED`` 로 만든다. 이 자리가 없으면 터진 실행이 ``SUCCEEDED`` 로
    남는다.
    """
    if crashed and held is not None:
        raise ValueError("보류와 실패를 함께 적을 수 없다 — 둘은 다음에 할 일이 다르다")
    now = datetime.now(UTC)
    with engine.begin() as connection:
        attempt_id = connection.execute(
            text("SELECT id FROM macro_collection_attempt WHERE attempt_key = :key"),
            {"key": attempt_key},
        ).scalar_one_or_none()
        if attempt_id is None:
            # **발행이 시도 행을 만들기 전에 거절해도 기록은 남겨야 한다.** 발행기는 판정을
            # 먼저 하고 시도 행을 나중에 만드는데, 그 판정에서 던지면 붙을 자리가 없어진다.
            # 그때 기록까지 버리면 무엇을 받았고 무엇이 실패했는지가 통째로 사라진다(FR-011).
            failed = any(not outcome.succeeded for outcome in outcomes)
            if crashed:
                status = "FAILED"
            elif held:
                status = "HELD"
            else:
                status = "PARTIAL" if failed else "SUCCEEDED"
            attempt_id = connection.execute(
                text(
                    "INSERT INTO macro_collection_attempt"
                    " (attempt_key, mode, started_at, finished_at, status, hold_reason)"
                    " VALUES (:key, :mode, :now, :now, :status, :hold) RETURNING id"
                ),
                {
                    "key": attempt_key,
                    "mode": mode,
                    "now": now,
                    "status": status,
                    "hold": held,
                },
            ).scalar_one()
        elif held is not None:
            # 발행기가 시도 행을 만든 뒤 보류로 끝난 경우다. 발행기는 상태만 적고 사유는
            # 예외로 올리므로, 그 사유가 여기서 행에 붙지 않으면 어디에도 남지 않는다.
            connection.execute(
                text(
                    "UPDATE macro_collection_attempt"
                    " SET status = 'HELD', hold_reason = :hold, finished_at = :now"
                    " WHERE id = :id"
                ),
                {"hold": held, "now": now, "id": attempt_id},
            )
        for outcome in outcomes:
            connection.execute(
                text(
                    "INSERT INTO macro_collection_item_status"
                    " (attempt_id, indicator_id, source, status, last_success_at,"
                    "  last_error_at, error_class, quota_exhausted, raw_location,"
                    "  excluded_row_count)"
                    " VALUES (:attempt, :indicator, :source, :status, :success,"
                    "  :error_at, :error_class, :quota, :raw, :excluded)"
                    " ON CONFLICT (attempt_id, indicator_id) DO UPDATE SET"
                    "  status = EXCLUDED.status, last_success_at = EXCLUDED.last_success_at,"
                    "  last_error_at = EXCLUDED.last_error_at,"
                    "  error_class = EXCLUDED.error_class,"
                    "  quota_exhausted = EXCLUDED.quota_exhausted,"
                    "  raw_location = EXCLUDED.raw_location,"
                    "  excluded_row_count = EXCLUDED.excluded_row_count"
                ),
                {
                    "attempt": attempt_id,
                    "indicator": outcome.indicator_id,
                    "source": outcome.source,
                    "status": outcome.status,
                    "success": now if outcome.succeeded else None,
                    "error_at": None if outcome.succeeded else now,
                    "error_class": outcome.error_class,
                    "quota": outcome.status == STATUS_QUOTA,
                    "raw": str(outcome.raw_documents[-1].path)
                    if outcome.raw_documents
                    else None,
                    "excluded": outcome.excluded_rows,
                },
            )


def rights_status(indicator: IndicatorDefinition) -> str | None:
    """권리 판정이 수집을 막는지. 막지 않으면 ``None`` 이다 (FR-016a)."""
    if indicator.license_review_status == registry.REVIEW_UNVERIFIED:
        return STATUS_RIGHTS_UNVERIFIED
    if indicator.license_review_status == registry.REVIEW_PENDING_RIGHTS:
        return STATUS_RIGHTS_PENDING
    return None


@dataclass(frozen=True)
class IngestReport:
    """한 번의 수집 실행이 남긴 것. 사람이 읽고 다음 행동을 정하는 데 쓴다."""

    attempt_key: str
    outcomes: tuple[IndicatorOutcome, ...]
    received_observations: int
    raw_revisions_written: int
    dataset_version_id: int | None
    publication_held: str | None

    @property
    def failed(self) -> tuple[IndicatorOutcome, ...]:
        return tuple(outcome for outcome in self.outcomes if not outcome.succeeded)

    @property
    def published(self) -> bool:
        return self.dataset_version_id is not None


@dataclass(frozen=True)
class Shortfall:
    """전 구간 적재가 기대에 못 미친 지표 하나 (FR-011b(3))."""

    indicator_id: str
    expected_months: int
    received_months: int
    expected_end_month: str

    def __str__(self) -> str:
        return (
            f"{self.indicator_id} {self.received_months}/{self.expected_months}개월"
            f" (~{self.expected_end_month})"
        )


#: 꼬리 쪽 한 달은 미달로 보지 않는다 (FR-011b(3)).
#:
#: 등록표의 공표 지연은 **2026-09-20 하루치 관측**으로 정한 값이다. 그날 관세청은 이번 달치를
#: 이미 내고 있었지만 달 초에는 아직 없고, 통계청 산업활동동향도 발표가 하루 이틀 밀리는 달이
#: 있다. 여유 없이 걸면 그런 날의 실행이 17개 전부를 발행하지 못한다.
#:
#: 여유를 두어 잃는 것은 작다. 이 판정이 막으려는 것은 **전 구간이 통째로 짧은 것**이다 —
#: 주택가격이 2000-01 대신 2021-06 부터만 오면 260개월이 빈다. 한 달의 여유로는 그런 구멍이
#: 가려지지 않는다. 반대로 여유가 없으면 발표 하루 차이가 발행 전체를 멈춘다.
TAIL_SLACK_MONTHS: Final[int] = 1


def shortfalls(
    indicators: Sequence[IndicatorDefinition],
    observations: Mapping[str, Sequence[NormalizedObservation]],
    as_of: str,
    *,
    slack_months: int = TAIL_SLACK_MONTHS,
) -> list[Shortfall]:
    """기대 대상월 수에 못 미친 지표들 (FR-011b(3)).

    **받은 대상월을 센다. 값이 있는 달이 아니다.** 전년동월 대비의 첫 열두 달은 비교월이 없어
    결측이지만 그 달들도 받은 대상월이고, 값만 세면 증가율 지표가 영영 미달로 남는다.

    기대보다 **많은** 것은 미달이 아니다 — 원천이 이번 달치를 먼저 낸 경우가 그렇고, 그것은
    문제가 아니라 공표 지연이 우리가 적어 둔 것보다 짧았다는 뜻이다.

    ``slack_months`` 만큼은 모자라도 넘어간다. 이유는 :data:`TAIL_SLACK_MONTHS` 에 있다.
    """
    result: list[Shortfall] = []
    for indicator in indicators:
        expected = registry.expected_month_count(indicator, as_of)
        received = len(observations.get(indicator.indicator_id, ()))
        if received < expected - slack_months:
            result.append(
                Shortfall(
                    indicator_id=indicator.indicator_id,
                    expected_months=expected,
                    received_months=received,
                    expected_end_month=registry.expected_end_month(indicator, as_of),
                )
            )
    return result


def run(
    engine: Engine,
    indicators: Sequence[IndicatorDefinition],
    *,
    fetcher: transport.Fetcher,
    data_root: Path,
    mode: str = "initial",
    version_key: str | None = None,
    today: date | None = None,
    configs: Mapping[str, SourceConfig] | None = None,
    trading_days: Mapping[str, Sequence[str]] | None = None,
) -> IngestReport:
    """수집부터 발행까지 한 번에 돈다 (T080·T081·T082).

    **발행이 보류돼도 기록은 남긴다.** 17개가 다 차지 않으면 스냅샷을 내보내지 않지만, 무엇을
    받았고 무엇이 실패했는지는 남아야 한다 — 그래야 다음 실행에서 무엇을 고칠지 알 수 있다.
    그래서 원본 이력과 지표별 상태를 발행 성공 여부와 무관하게 쓴다(FR-011).

    순서가 중요하다. `publish_atomically` 가 수집 시도 행을 만들기 때문에 지표별 상태는 그
    뒤에 쓴다. 발행이 보류로 끝나도 시도 행은 남으므로 상태를 붙일 자리는 있다.
    """
    today = today or datetime.now(UTC).date()
    received_at = datetime.now(UTC)
    attempt_key = f"{mode}-{received_at:%Y%m%dT%H%M%S%f}"
    resolved_version = version_key or f"macro-{received_at:%Y%m%dT%H%M%S}"

    outcomes = collect(
        indicators,
        fetcher=fetcher,
        data_root=data_root,
        mode=mode,
        today=today,
        configs=configs,
    )
    observations = normalize_outcomes(
        indicators, outcomes, as_of=today, trading_days=trading_days, received_at=received_at
    )
    raw_written = record_raw_revisions(engine, outcomes)
    pointer = _current_pointer(engine)
    if mode == "regular" and pointer.dataset_id is not None:
        observations = merge_published_observations(
            engine, pointer.dataset_id, indicators, observations
        )
    processed_files = tuple(write_processed(observations, data_root))

    raw_files = tuple(
        document.path for outcome in outcomes for document in outcome.raw_documents
    )
    request = publish.AtomicPublicationRequest(
        version_key=resolved_version,
        attempt_key=attempt_key,
        pointer=pointer,
        indicators=tuple(indicators),
        observations=observations,
        raw_files=raw_files,
        processed_files=processed_files,
        data_root=data_root,
    )

    dataset_id: int | None = None
    held: str | None = None
    # **전 구간 적재는 기대 대상월 수를 채워야 완료다**(FR-011b(3)). 한 지표라도 모자라면
    # 발행하지 않는다 — 짧은 계열로 낸 스냅샷은 응답도 값도 멀쩡해서, 과거가 잘려 있다는
    # 사실이 화면 어디에도 드러나지 않는다. 정기 수집은 최근 N개월만 받으므로 해당 없다.
    missing_months = (
        shortfalls(indicators, observations, f"{today.year:04d}-{today.month:02d}")
        if mode == "initial"
        else []
    )
    if len(indicators) != registry.EXPECTED_INDICATOR_COUNT:
        # **일부만 받은 실행은 발행을 시도하지 않는다.** 발행은 17개가 함께여야 성립하므로
        # (FR-011) 부분 집합으로 부르면 판정기가 그 자리에서 거절한다. 그것을 실패로 적으면
        # 지표 하나를 확인하려고 돌린 실행이 늘 실패로 보인다 — 진단 경로는 진단으로 끝낸다.
        held = (
            f"부분 수집이라 발행하지 않는다: {len(indicators)}개"
            f" (발행은 {registry.EXPECTED_INDICATOR_COUNT}개가 함께여야 한다)"
        )
        logger.info("%s", held)
    elif missing_months:
        held = "전 구간 적재가 기대에 못 미쳐 발행하지 않는다: " + ", ".join(
            str(shortfall) for shortfall in missing_months
        )
        logger.warning("%s", held)
    else:
        try:
            dataset_id = publish.publish_atomically(engine, request)
        except publish.PublishError as error:
            held = str(error)
            logger.warning("발행 보류: %s", error)
        except Exception:
            # **발행이 어떻게 죽든 지표별 기록은 남긴다.** 발행기가 시도 행을 FAILED 로
            # 적어 주므로 실행이 실패했다는 사실 자체는 사라지지 않지만, 여기서 그냥
            # 올려보내면 아래의 기록이 건너뛰어져 **무엇을 받았고 무엇이 실패했는지가
            # 통째로 없어진다**(FR-011). 그러면 남는 것은 "실패했다" 한 줄뿐이라 다음
            # 실행에서 무엇을 다시 받아야 하는지 알 수 없다.
            record_item_status(engine, attempt_key, outcomes, mode=mode.upper(), crashed=True)
            raise

    record_item_status(engine, attempt_key, outcomes, mode=mode.upper(), held=held)

    return IngestReport(
        attempt_key=attempt_key,
        outcomes=tuple(outcomes),
        received_observations=sum(len(o.observations) for o in outcomes),
        raw_revisions_written=raw_written,
        dataset_version_id=dataset_id,
        publication_held=held,
    )


def _current_pointer(engine: Engine) -> publish.PublicationPointer:
    """지금 공개 중인 세 버전. 없으면 등록표부터 세우라고 말한다."""
    with engine.begin() as connection:
        row = connection.execute(
            text(
                "SELECT dataset_version_id, registry_version_id, display_version_id"
                " FROM macro_current_publication WHERE singleton_key = 'X'"
            )
        ).first()
    if row is None:
        raise ValueError(
            "현재 포인터가 없다. versioning.register_calculation_version 과"
            " initialize_current_publication 으로 등록표·표시 버전을 먼저 세운다"
        )
    return publish.PublicationPointer(
        dataset_id=row[0], registry_id=row[1], display_id=row[2]
    )
