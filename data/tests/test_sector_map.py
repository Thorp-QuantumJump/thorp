"""섹터-ETF 대응표 파이프라인 (spec 001 T005, FR-001·FR-002·FR-003, SC-001).

실제 PostgreSQL 없이 돌린다. 버전 여닫는 순서·멱등·값 검사는 모두 파이썬 계층의 규칙이라
인메모리 SQLite에 같은 열로 표를 만들어 확인할 수 있다. DB CHECK·FK는 마이그레이션이 원본이며
`backend`의 `EtfMarketDataSchemaIT`가 PostgreSQL 컨테이너로 검증한다.
"""

from __future__ import annotations

import dataclasses
import importlib.util
import json
import re
import sys
from collections.abc import Iterator
from datetime import UTC, date, datetime
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest
from sqlalchemy import Table, create_engine, func, select

from data_pipeline.sector_map import (
    MAP_VERSION_PATTERN,
    SAMPLE_SECTOR_MAP_PATH,
    SECTOR_CODE_PATTERN,
    SECTOR_METADATA,
    ConflictingEtfAttributesError,
    DuplicateSectorError,
    LeveragedEtfRejectedError,
    RepresentativeEtf,
    SectorEtfMap,
    SectorMapEntry,
    SectorMapError,
    SectorMapFileError,
    SectorMapRepository,
    SectorMapVersionConflictError,
    SectorMasterConflictError,
    load_sector_map,
    sector_etf_map_entry_table,
    sector_etf_map_version_table,
    sector_map_from_payload,
    sector_table,
    validate_map_version,
)

DATA_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = DATA_ROOT.parent
SCRIPT_PATH = DATA_ROOT / "scripts/ingest_sector_map.py"

FIRST = datetime(2026, 9, 11, 9, 0, tzinfo=UTC)
SECOND = datetime(2026, 9, 12, 9, 0, tzinfo=UTC)
THIRD = datetime(2026, 9, 13, 9, 0, tzinfo=UTC)

# 파일명을 고정하지 않고 찾는다. 마이그레이션 버전이 타임스탬프라 파일명이 바뀔 수 있는데,
# 고정해 두면 이름이 달라진 순간 아래 대조가 조용히 건너뛰어져 표류를 놓친다.
_MIGRATIONS = sorted(
    (REPO_ROOT / "backend/src/main/resources/db/migration").glob("V*__etf_market_data.sql")
)
MIGRATION = _MIGRATIONS[-1] if _MIGRATIONS else None


# ---------------------------------------------------------------------------
# 대역
# ---------------------------------------------------------------------------


def etf_payload(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "instrument_id": "091160.XKRX",
        "name": "KODEX 반도체",
        "listed_on": "2006-06-27",
        "benchmark_index": "KRX 반도체",
        "manager": "삼성자산운용",
    }
    payload.update(overrides)
    return payload


def sector_payload(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "code": "SEMICONDUCTOR",
        "name": "반도체",
        "classification": "INDUSTRY",
        "representative_etf": etf_payload(),
    }
    payload.update(overrides)
    return payload


def map_payload(sectors: list[dict[str, Any]] | None = None, **overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "is_sample": True,
        "sectors": sectors if sectors is not None else [sector_payload()],
    }
    payload.update(overrides)
    return payload


def write_map(path: Path, payload: dict[str, Any]) -> Path:
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path


# 고치기 전의 표기. 위의 대역과 달리 손으로 적어 둔다 — 이 값들은 "지금의 계약"이 아니라 "다시
# 받아들이면 안 되는 옛 모양"이라, 계약이 또 바뀌어도 따라 움직이면 안 된다.
CAMEL_MAP_PAYLOAD: dict[str, Any] = {
    "isSample": True,
    "sectors": [
        {
            "code": "SEMICONDUCTOR",
            "name": "반도체",
            "classification": "INDUSTRY",
            "representativeEtf": {
                "instrumentId": "091160.XKRX",
                "name": "KODEX 반도체",
                "listedOn": "2006-06-27",
                "delistedOn": None,
                "benchmarkIndex": "KRX 반도체",
                "manager": "삼성자산운용",
            },
        }
    ],
}
#: 바깥은 맞게 적고 섹터의 키만 옛 표기로 적은 파일.
CAMEL_SECTOR_KEY_PAYLOAD: dict[str, Any] = {
    "is_sample": True,
    "sectors": [
        {
            "code": "SEMICONDUCTOR",
            "name": "반도체",
            "classification": "INDUSTRY",
            "representativeEtf": {
                "instrument_id": "091160.XKRX",
                "name": "KODEX 반도체",
                "listed_on": "2006-06-27",
                "benchmark_index": "KRX 반도체",
                "manager": "삼성자산운용",
            },
        }
    ],
}
#: 대표 ETF 안쪽만 옛 표기로 적은 파일. 중첩이 두 겹이라 안쪽 이름도 따로 굳힌다.
CAMEL_ETF_KEY_PAYLOAD: dict[str, Any] = {
    "is_sample": True,
    "sectors": [
        {
            "code": "SEMICONDUCTOR",
            "name": "반도체",
            "classification": "INDUSTRY",
            "representative_etf": {
                "instrumentId": "091160.XKRX",
                "name": "KODEX 반도체",
                "listedOn": "2006-06-27",
                "benchmarkIndex": "KRX 반도체",
                "manager": "삼성자산운용",
            },
        }
    ],
}


def make_etf(**overrides: Any) -> RepresentativeEtf:
    values: dict[str, Any] = {
        "instrument_id": "091160.XKRX",
        "name": "KODEX 반도체",
        "listed_on": date(2006, 6, 27),
        "benchmark_index": "KRX 반도체",
        "manager": "삼성자산운용",
    }
    values.update(overrides)
    return RepresentativeEtf(**values)


def make_entry(**overrides: Any) -> SectorMapEntry:
    values: dict[str, Any] = {
        "code": "SEMICONDUCTOR",
        "name": "반도체",
        "classification": "INDUSTRY",
        "etf": make_etf(),
    }
    values.update(overrides)
    return SectorMapEntry(**values)


def make_map(*entries: SectorMapEntry, is_sample: bool = True) -> SectorEtfMap:
    return SectorEtfMap.of(entries or (make_entry(),), is_sample=is_sample)


@pytest.fixture
def repository() -> Iterator[SectorMapRepository]:
    engine = create_engine("sqlite://")
    SECTOR_METADATA.create_all(engine)
    try:
        yield SectorMapRepository(engine)
    finally:
        engine.dispose()


@pytest.fixture(scope="module")
def entry_point() -> ModuleType:
    """진입점을 파일 경로로 읽어 온다. ``scripts/``는 패키지가 아니라 import 경로가 아니다."""
    spec = importlib.util.spec_from_file_location("ingest_sector_map", SCRIPT_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def count_rows(repository: SectorMapRepository, table: Table) -> int:
    with repository.engine.connect() as conn:
        return int(conn.execute(select(func.count()).select_from(table)).scalar_one())


def version_rows(repository: SectorMapRepository) -> list[Any]:
    with repository.engine.connect() as conn:
        return list(
            conn.execute(
                select(sector_etf_map_version_table).order_by(sector_etf_map_version_table.c.id)
            )
        )


# ---------------------------------------------------------------------------
# 예시 대응표 (FR-002)
# ---------------------------------------------------------------------------


class TestSampleFixture:
    def test_sample_map_loads(self) -> None:
        sector_map = load_sector_map(SAMPLE_SECTOR_MAP_PATH)
        assert sector_map.is_sample is True
        assert len(sector_map.entries) >= 2

    def test_every_sector_has_exactly_one_representative_etf(self) -> None:
        """SC-001. 섹터마다 대표 ETF가 정확히 하나여야 한다."""
        sector_map = load_sector_map(SAMPLE_SECTOR_MAP_PATH)
        assert len(set(sector_map.codes)) == len(sector_map.entries)
        for entry in sector_map.entries:
            assert isinstance(entry.etf, RepresentativeEtf)

    def test_listing_date_and_benchmark_index_are_filled(self) -> None:
        """FR-001의 필수 항목. 상장일과 기준 지수가 비어 있으면 대응표로 쓸 수 없다."""
        sector_map = load_sector_map(SAMPLE_SECTOR_MAP_PATH)
        for entry in sector_map.entries:
            assert isinstance(entry.etf.listed_on, date)
            assert entry.etf.benchmark_index.strip()
            assert entry.etf.manager.strip()

    def test_sample_map_has_no_leveraged_or_inverse_etf(self) -> None:
        sector_map = load_sector_map(SAMPLE_SECTOR_MAP_PATH)
        for entry in sector_map.entries:
            assert entry.etf.leveraged is False
            assert entry.etf.inverse is False

    def test_sample_map_says_it_is_provisional(self) -> None:
        """FR-002. 예시임과 PD-01에서 확정된다는 사실이 파일 안에 드러나야 한다."""
        raw = json.loads(SAMPLE_SECTOR_MAP_PATH.read_text(encoding="utf-8"))
        assert raw["is_sample"] is True
        commentary = " ".join(value for key, value in raw.items() if key.startswith("$"))
        assert "PD-01" in commentary

    def test_classification_covers_both_values(self) -> None:
        sector_map = load_sector_map(SAMPLE_SECTOR_MAP_PATH)
        assert {entry.classification for entry in sector_map.entries} == {"INDUSTRY", "THEME"}


# ---------------------------------------------------------------------------
# 입력 파일의 키 표기
# ---------------------------------------------------------------------------


class TestInputFileKeys:
    """대응표 파일의 **자료 키**도 snake_case 한 가지다 (2026-09-13 결정).

    일봉·기업행위·대표 상품 정보 파일은 ``tests/test_ingest_krx_inputs.py``의
    ``TestInputFileKeys``가 같은 것을 고정한다. 루트 ``CLAUDE.md``의 camelCase 규칙은 API
    JSON을 대상으로 한 것이라(``contracts/sector-api.md``) 입력 파일에는 해당하지 않는다.

    조용히 둘 다 받아들이면 어느 표기가 규약인지 알 수 없게 되고, 한 파일에 두 표기가 섞여도
    드러나지 않는다.

    **``$``로 시작하는 키는 대상이 아니다.** 파서가 읽기 전에 걷어내는 주석이고, 표본 파일과
    ``contracts/fixtures/``의 다른 픽스처가 그 주석에 camelCase를 쓴다. 한 파일만 바꾸면
    픽스처끼리 표기가 갈리므로 주석 표기를 통일할지는 따로 정한다.
    """

    def test_the_sample_map_file_uses_snake_case(self) -> None:
        # 사람이 새 대응표를 만들 때 베끼는 파일이다. 여기에 옛 표기가 남으면 코드가 받지 않는
        # 파일을 만들게 된다.
        text = SAMPLE_SECTOR_MAP_PATH.read_text(encoding="utf-8")
        for camel in (
            "instrumentId",
            "listedOn",
            "delistedOn",
            "benchmarkIndex",
            "representativeEtf",
            "isSample",
        ):
            assert f'"{camel}"' not in text, f"예시 대응표에 옛 표기가 남아 있다: {camel}"
        assert load_sector_map(SAMPLE_SECTOR_MAP_PATH).is_sample is True

    def test_a_camel_case_sector_map_file_is_refused(self, tmp_path: Path) -> None:
        path = write_map(tmp_path / "map.json", CAMEL_MAP_PAYLOAD)
        with pytest.raises(SectorMapFileError, match="is_sample"):
            load_sector_map(path)

    def test_a_camel_case_sector_key_is_refused(self) -> None:
        with pytest.raises(SectorMapFileError, match="representativeEtf"):
            sector_map_from_payload(CAMEL_SECTOR_KEY_PAYLOAD)

    def test_camel_case_keys_inside_the_representative_etf_are_refused(self) -> None:
        with pytest.raises(SectorMapFileError, match="instrument_id"):
            sector_map_from_payload(CAMEL_ETF_KEY_PAYLOAD)


# ---------------------------------------------------------------------------
# 값 검사
# ---------------------------------------------------------------------------


class TestValidation:
    def test_duplicate_sector_is_rejected(self) -> None:
        """SC-001. 한 섹터에 대표 ETF가 둘 붙으면 거부한다."""
        other = etf_payload(instrument_id="091170.XKRX", name="KODEX 은행")
        with pytest.raises(DuplicateSectorError, match="SEMICONDUCTOR"):
            sector_map_from_payload(
                map_payload([sector_payload(), sector_payload(representative_etf=other)])
            )

    def test_sector_without_representative_etf_is_rejected(self) -> None:
        payload = sector_payload()
        del payload["representative_etf"]
        with pytest.raises(SectorMapFileError, match="representative_etf"):
            sector_map_from_payload(map_payload([payload]))

    def test_leveraged_etf_is_rejected_with_reason(self) -> None:
        """FR-001. 거부 근거를 예외에 남긴다."""
        with pytest.raises(LeveragedEtfRejectedError) as caught:
            sector_map_from_payload(
                map_payload([sector_payload(representative_etf=etf_payload(leveraged=True))])
            )
        assert caught.value.reasons == ("leveraged=True",)
        assert caught.value.instrument_id == "091160.XKRX"
        assert "FR-001" in str(caught.value)

    def test_inverse_etf_is_rejected_with_reason(self) -> None:
        with pytest.raises(LeveragedEtfRejectedError) as caught:
            sector_map_from_payload(
                map_payload([sector_payload(representative_etf=etf_payload(inverse=True))])
            )
        assert caught.value.reasons == ("inverse=True",)

    def test_leveraged_and_inverse_reasons_are_both_recorded(self) -> None:
        with pytest.raises(LeveragedEtfRejectedError) as caught:
            make_etf(leveraged=True, inverse=True)
        assert caught.value.reasons == ("leveraged=True", "inverse=True")

    def test_missing_listing_date_is_rejected(self) -> None:
        payload = etf_payload()
        del payload["listed_on"]
        with pytest.raises(SectorMapFileError, match="listed_on"):
            sector_map_from_payload(map_payload([sector_payload(representative_etf=payload)]))

    def test_null_listing_date_is_rejected(self) -> None:
        with pytest.raises(SectorMapFileError, match="listed_on"):
            sector_map_from_payload(
                map_payload([sector_payload(representative_etf=etf_payload(listed_on=None))])
            )

    def test_missing_benchmark_index_is_rejected(self) -> None:
        payload = etf_payload()
        del payload["benchmark_index"]
        with pytest.raises(SectorMapFileError, match="benchmark_index"):
            sector_map_from_payload(map_payload([sector_payload(representative_etf=payload)]))

    def test_blank_benchmark_index_is_rejected(self) -> None:
        with pytest.raises(SectorMapError, match="기준 지수"):
            sector_map_from_payload(
                map_payload([sector_payload(representative_etf=etf_payload(benchmark_index="  "))])
            )

    def test_missing_manager_is_rejected(self) -> None:
        payload = etf_payload()
        del payload["manager"]
        with pytest.raises(SectorMapFileError, match="manager"):
            sector_map_from_payload(map_payload([sector_payload(representative_etf=payload)]))

    def test_instrument_id_format_is_enforced(self) -> None:
        with pytest.raises(SectorMapError, match="XKRX"):
            make_etf(instrument_id="091160")

    def test_unknown_classification_is_rejected(self) -> None:
        with pytest.raises(SectorMapError, match="classification"):
            make_entry(classification="SECTOR")

    def test_unknown_key_is_rejected(self) -> None:
        with pytest.raises(SectorMapFileError, match="mapVersion"):
            sector_map_from_payload(map_payload(mapVersion="SEM-2026-09-11-001"))

    def test_commentary_keys_are_ignored(self) -> None:
        payload = map_payload()
        payload["$note"] = "PD-01 확정 전 예시"
        assert sector_map_from_payload(payload).is_sample is True

    def test_delisted_before_listed_is_rejected(self) -> None:
        with pytest.raises(SectorMapError, match="상장폐지일"):
            make_etf(delisted_on=date(2005, 1, 3))

    def test_empty_map_is_rejected(self) -> None:
        with pytest.raises(SectorMapError, match="항목이 하나도 없다"):
            sector_map_from_payload(map_payload([]))

    def test_map_version_name_is_validated(self) -> None:
        assert validate_map_version("SEM-2026-09-11-001") == "SEM-2026-09-11-001"
        with pytest.raises(SectorMapError):
            validate_map_version("SEM 2026/09/11")

    def test_fingerprint_ignores_entry_order(self) -> None:
        bank = make_entry(
            code="BANK",
            name="은행",
            etf=make_etf(instrument_id="091170.XKRX", name="KODEX 은행"),
        )
        semiconductor = make_entry()
        forward = make_map(semiconductor, bank).fingerprint()
        backward = make_map(bank, semiconductor).fingerprint()
        assert forward == backward

    def test_fingerprint_changes_with_is_sample(self) -> None:
        """FR-002. 예시에서 확정으로 바뀌는 것도 내용 변경이라 새 버전이어야 한다."""
        assert make_map(is_sample=True).fingerprint() != make_map(is_sample=False).fingerprint()


# ---------------------------------------------------------------------------
# 버전 발행 (FR-003)
# ---------------------------------------------------------------------------


class TestPublishing:
    def test_first_publish_opens_a_version(self, repository: SectorMapRepository) -> None:
        result = repository.publish(make_map(), published_at=FIRST)
        assert result.created is True
        assert result.version == "SEM-2026-09-11-001"
        assert result.entry_count == 1
        assert result.closed_version is None
        rows = version_rows(repository)
        assert len(rows) == 1
        assert rows[0].effective_to is None
        assert bool(rows[0].is_sample) is True

    def test_explicit_version_name_is_used(self, repository: SectorMapRepository) -> None:
        result = repository.publish(make_map(), version="PD01-CANDIDATE", published_at=FIRST)
        assert result.version == "PD01-CANDIDATE"

    def test_changed_content_opens_a_new_version(self, repository: SectorMapRepository) -> None:
        """FR-003. 기존 버전을 고치지 않고 새 버전을 만든다."""
        first = repository.publish(make_map(), published_at=FIRST)
        bank = make_entry(
            code="BANK",
            name="은행",
            etf=make_etf(instrument_id="091170.XKRX", name="KODEX 은행"),
        )
        second = repository.publish(make_map(make_entry(), bank), published_at=SECOND)

        assert second.created is True
        assert second.closed_version == first.version
        assert second.map_version_id != first.map_version_id

        rows = version_rows(repository)
        assert len(rows) == 2
        closed, opened = rows
        assert closed.version == first.version
        assert closed.effective_to is not None
        assert opened.effective_to is None
        # 옛 버전의 항목은 그대로 남는다. 발행된 버전은 고치지 않는다.
        assert count_rows(repository, sector_etf_map_entry_table) == 3

    def test_only_one_version_stays_open(self, repository: SectorMapRepository) -> None:
        bank = make_entry(
            code="BANK",
            name="은행",
            etf=make_etf(instrument_id="091170.XKRX", name="KODEX 은행"),
        )
        repository.publish(make_map(), published_at=FIRST)
        repository.publish(make_map(is_sample=False), published_at=SECOND)
        repository.publish(make_map(make_entry(), bank, is_sample=False), published_at=THIRD)
        rows = version_rows(repository)
        assert len(rows) == 3
        assert sum(1 for row in rows if row.effective_to is None) == 1

    def test_republishing_the_same_content_is_idempotent(
        self, repository: SectorMapRepository
    ) -> None:
        first = repository.publish(make_map(), published_at=FIRST)
        second = repository.publish(make_map(), published_at=SECOND)
        assert second.created is False
        assert second.version == first.version
        assert second.map_version_id == first.map_version_id
        assert len(version_rows(repository)) == 1
        assert count_rows(repository, sector_etf_map_entry_table) == 1

    def test_same_content_under_a_new_name_does_not_open_a_version(
        self, repository: SectorMapRepository
    ) -> None:
        """버전을 가르는 것은 내용이고 이름은 별칭이다."""
        repository.publish(make_map(), version="SEM-A", published_at=FIRST)
        result = repository.publish(make_map(), version="SEM-B", published_at=SECOND)
        assert result.created is False
        assert result.version == "SEM-A"
        assert len(version_rows(repository)) == 1

    def test_reusing_a_version_name_for_new_content_is_rejected(
        self, repository: SectorMapRepository
    ) -> None:
        repository.publish(make_map(), version="SEM-A", published_at=FIRST)
        with pytest.raises(SectorMapVersionConflictError, match="SEM-A"):
            repository.publish(make_map(is_sample=False), version="SEM-A", published_at=SECOND)
        assert len(version_rows(repository)) == 1

    def test_version_sequence_increases_within_a_day(self, repository: SectorMapRepository) -> None:
        """이름의 날짜는 한국 기준이다. FIRST(09-11 09:00 UTC)는 한국 09-11 18:00이고
        later(09-11 14:00 UTC)는 한국 09-11 23:00이라 같은 날이다."""
        repository.publish(make_map(), published_at=FIRST)
        later = FIRST.replace(hour=14)
        result = repository.publish(make_map(is_sample=False), published_at=later)
        assert result.version == "SEM-2026-09-11-002"

    def test_version_label_uses_the_korean_date(self, repository: SectorMapRepository) -> None:
        """UTC로 자르면 한국 오전 0~9시 발행이 전날 이름을 받는다.

        같은 행의 effective_from을 읽는 쪽(worker의 _as_date)은 한국 기준으로 자르므로,
        고치지 않으면 이름과 유효일이 하루 어긋나고 전날 순번과도 부딪힌다.
        """
        # 한국 2026-09-12 오전 8시
        moment = datetime(2026, 9, 11, 23, 0, tzinfo=UTC)
        result = repository.publish(make_map(), published_at=moment)
        assert result.version == "SEM-2026-09-12-001"

    def test_publishing_before_the_open_version_started_is_rejected(
        self, repository: SectorMapRepository
    ) -> None:
        """닫는 시각이 시작 시각보다 뒤여야 한다 (ck_sector_etf_map_version_period)."""
        repository.publish(make_map(), published_at=SECOND)
        with pytest.raises(SectorMapError, match="effective_to > effective_from"):
            repository.publish(make_map(is_sample=False), published_at=FIRST)

    def test_naive_published_at_is_rejected(self, repository: SectorMapRepository) -> None:
        with pytest.raises(SectorMapError, match="시간대"):
            repository.publish(make_map(), published_at=datetime(2026, 9, 11, 9, 0))

    def test_one_etf_can_serve_several_sectors(self, repository: SectorMapRepository) -> None:
        """data-model.md가 허용한다. 구성종목 중복은 대표 상품 정보가 알린다 (FR-015)."""
        twin = make_entry(code="MEMORY", name="메모리")
        result = repository.publish(make_map(make_entry(), twin), published_at=FIRST)
        assert result.entry_count == 2
        assert count_rows(repository, sector_etf_map_entry_table) == 2

    def test_current_version_reads_back_what_was_published(
        self, repository: SectorMapRepository
    ) -> None:
        published = make_map()
        repository.publish(published, published_at=FIRST)
        current = repository.current_version()
        assert current is not None
        version, stored = current
        assert version == "SEM-2026-09-11-001"
        assert stored.fingerprint() == published.fingerprint()

    def test_current_version_is_none_before_any_publish(
        self, repository: SectorMapRepository
    ) -> None:
        assert repository.current_version() is None

    def test_changing_a_stored_sector_attribute_is_rejected(
        self, repository: SectorMapRepository
    ) -> None:
        repository.publish(make_map(), published_at=FIRST)
        with pytest.raises(SectorMasterConflictError, match="SEMICONDUCTOR"):
            repository.publish(make_map(make_entry(name="반도체·장비")), published_at=SECOND)

    def test_changing_a_stored_etf_attribute_is_rejected(
        self, repository: SectorMapRepository
    ) -> None:
        repository.publish(make_map(), published_at=FIRST)
        with pytest.raises(SectorMasterConflictError, match="091160.XKRX"):
            repository.publish(
                make_map(make_entry(etf=make_etf(listed_on=date(2006, 6, 28)))),
                published_at=SECOND,
            )

    def test_a_rejected_publish_leaves_nothing_behind(
        self, repository: SectorMapRepository
    ) -> None:
        """닫기와 열기가 한 트랜잭션이라 중간에 실패하면 아무것도 남지 않는다."""
        repository.publish(make_map(), published_at=FIRST)
        fresh = make_entry(
            code="BANK",
            name="은행",
            etf=make_etf(instrument_id="091170.XKRX", name="KODEX 은행"),
        )
        conflicting = make_entry(name="반도체·장비")
        with pytest.raises(SectorMasterConflictError):
            repository.publish(make_map(fresh, conflicting), published_at=SECOND)

        rows = version_rows(repository)
        assert len(rows) == 1
        assert rows[0].effective_to is None
        assert count_rows(repository, sector_table) == 1

    def test_sample_map_publishes(self, repository: SectorMapRepository) -> None:
        sector_map = load_sector_map(SAMPLE_SECTOR_MAP_PATH)
        result = repository.publish(sector_map, published_at=FIRST)
        assert result.created is True
        assert result.entry_count == len(sector_map.entries)


# ---------------------------------------------------------------------------
# 진입점 (data/CLAUDE.md 「재실행 안전」)
# ---------------------------------------------------------------------------


class TestEntryPoint:
    def test_sample_publishes_and_reruns_idempotently(
        self, entry_point: ModuleType, repository: SectorMapRepository, capsys: Any
    ) -> None:
        assert entry_point.main(["--sample"], repository=repository) == 0
        assert entry_point.main(["--sample"], repository=repository) == 0
        assert len(version_rows(repository)) == 1
        assert "새 버전을 만들지 않았다" in capsys.readouterr().out

    def test_map_version_argument_is_passed_through(
        self, entry_point: ModuleType, repository: SectorMapRepository
    ) -> None:
        assert (
            entry_point.main(
                ["--sample", "--map-version", "SEM-2026-09-11-009"], repository=repository
            )
            == 0
        )
        assert version_rows(repository)[0].version == "SEM-2026-09-11-009"

    def test_dry_run_does_not_touch_the_repository(
        self, entry_point: ModuleType, repository: SectorMapRepository, capsys: Any
    ) -> None:
        assert entry_point.main(["--sample", "--dry-run"], repository=repository) == 0
        assert version_rows(repository) == []
        assert "--dry-run" in capsys.readouterr().out

    def test_leveraged_map_is_rejected_with_a_reason(
        self,
        entry_point: ModuleType,
        repository: SectorMapRepository,
        tmp_path: Path,
        capsys: Any,
    ) -> None:
        path = tmp_path / "leveraged.json"
        path.write_text(
            json.dumps(
                map_payload(
                    [
                        sector_payload(
                            representative_etf=etf_payload(
                                instrument_id="122630.XKRX",
                                name="KODEX 레버리지",
                                leveraged=True,
                            )
                        )
                    ]
                ),
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        assert entry_point.main(["--map-file", str(path)], repository=repository) == 1
        captured = capsys.readouterr()
        assert "leveraged=True" in captured.err
        assert "122630.XKRX" in captured.err
        assert version_rows(repository) == []

    def test_sample_flag_requires_a_sample_map(
        self,
        entry_point: ModuleType,
        repository: SectorMapRepository,
        tmp_path: Path,
        monkeypatch: pytest.MonkeyPatch,
        capsys: Any,
    ) -> None:
        path = tmp_path / "final.json"
        path.write_text(
            json.dumps(map_payload(is_sample=False), ensure_ascii=False), encoding="utf-8"
        )
        monkeypatch.setattr(entry_point, "SAMPLE_SECTOR_MAP_PATH", path)
        assert entry_point.main(["--sample"], repository=repository) == 1
        assert "is_sample" in capsys.readouterr().err

    def test_a_source_argument_is_required(self, entry_point: ModuleType) -> None:
        with pytest.raises(SystemExit):
            entry_point.main([])

    def test_missing_map_file_is_reported(
        self, entry_point: ModuleType, repository: SectorMapRepository, tmp_path: Path, capsys: Any
    ) -> None:
        missing = tmp_path / "nope.json"
        assert entry_point.main(["--map-file", str(missing)], repository=repository) == 1
        assert "대응표 파일이 없다" in capsys.readouterr().err


# ---------------------------------------------------------------------------
# 저장 스키마와의 표류
# ---------------------------------------------------------------------------

_NOT_A_COLUMN = ("CONSTRAINT", "CHECK", "PRIMARY", "UNIQUE", "FOREIGN")


def parse_migration_columns(table_name: str) -> list[str]:
    """마이그레이션에서 한 표의 열 이름을 읽는다. 코드와 스키마의 표류를 잡는다."""
    assert MIGRATION is not None, (
        "V*__etf_market_data.sql을 찾지 못했다. 마이그레이션이 사라졌거나 이름 규칙이 바뀌었다면"
        " 이 대조를 건너뛰지 말고 여기를 고쳐야 한다"
    )
    sql = MIGRATION.read_text(encoding="utf-8")
    match = re.search(rf"CREATE TABLE {table_name} \((.*?)\n\);", sql, flags=re.DOTALL)
    assert match is not None, f"{table_name} 정의를 찾지 못했다"
    body = "\n".join(line.split("--")[0] for line in match.group(1).splitlines())

    items: list[str] = []
    depth = 0
    current: list[str] = []
    for character in body:
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        if character == "," and depth == 0:
            items.append("".join(current))
            current = []
            continue
        current.append(character)
    items.append("".join(current))

    columns: list[str] = []
    for item in items:
        stripped = item.strip()
        if not stripped or stripped.upper().startswith(_NOT_A_COLUMN):
            continue
        columns.append(stripped.split()[0])
    return columns


@pytest.mark.parametrize(
    "table",
    sorted(SECTOR_METADATA.tables.values(), key=lambda item: item.name),
    ids=lambda table: table.name,
)
def test_columns_match_the_migration(table: Table) -> None:
    assert sorted(column.name for column in table.columns) == sorted(
        parse_migration_columns(table.name)
    )


def test_no_credentials_are_hardcoded() -> None:
    source = (DATA_ROOT / "src/data_pipeline/sector_map.py").read_text(encoding="utf-8")
    assert 'create_engine("postgresql' not in source


class TestCodeReviewFixes:
    """코드리뷰(2026-09-11)에서 나온 지적을 막는 회귀 테스트."""

    def test_same_etf_with_different_attributes_is_rejected(self) -> None:
        """대응표를 만드는 자리에서 막는다.

        representative_etf는 종목 하나에 행 하나라 두 값을 담을 자리가 없다. 막지 않으면 먼저 온
        쪽만 저장되고 뒤엣것이 조용히 사라진다.
        """
        first = make_entry()
        twin = make_entry(code="MEMORY", name="메모리")
        conflicting = dataclasses.replace(
            twin, etf=dataclasses.replace(twin.etf, name="KODEX 반도체(오타)")
        )
        with pytest.raises(ConflictingEtfAttributesError):
            make_map(first, conflicting)

    def test_same_etf_with_identical_attributes_is_allowed(self) -> None:
        """같은 종목이 여러 섹터에 붙는 것 자체는 data-model.md가 허용한다."""
        published = make_map(make_entry(), make_entry(code="MEMORY", name="메모리"))
        assert len(published.entries) == 2

    def test_republishing_stays_idempotent_when_an_etf_serves_two_sectors(
        self, repository: SectorMapRepository
    ) -> None:
        """지적 1의 본체. 고치기 전에는 돌릴 때마다 새 버전이 열렸다."""
        published = make_map(make_entry(), make_entry(code="MEMORY", name="메모리"))
        first = repository.publish(published, published_at=FIRST)
        again = repository.publish(published, published_at=SECOND)
        assert again.version == first.version
        assert count_rows(repository, sector_etf_map_version_table) == 1

    def test_trailing_newline_in_sector_code_is_rejected(self) -> None:
        """파이썬의 ``$``는 끝에 붙은 줄바꿈 앞에서도 맞는다.

        자바 쪽 SectorCode는 String.matches()라 거부하므로, 그대로 두면 파이프라인이 넣을 수
        있는데 API가 읽지 못하는 코드가 생긴다. sector.code에는 형식 CHECK도 없다.
        """
        assert SECTOR_CODE_PATTERN.match("SEMICONDUCTOR\n") is None
        with pytest.raises(SectorMapError):
            make_entry(code="SEMICONDUCTOR\n")

    def test_trailing_newline_in_map_version_is_rejected(self) -> None:
        assert MAP_VERSION_PATTERN.match("SEM-2026-09-11-001\n") is None
