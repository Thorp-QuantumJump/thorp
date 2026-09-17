"""자료 버전 발행 규칙 (spec 001 FR-013·FR-014, research.md 결정 4)."""

from datetime import UTC, date, datetime, timedelta

import pytest

from data_pipeline.dataset_version import (
    DATASET_VERSION_COLUMNS,
    SOURCE_KRX_OPEN_API,
    DatasetVersion,
    DatasetVersionError,
    IncompleteWithoutReasonError,
    InvalidDatasetIdError,
    build_dataset_id,
    dataset_deadline_date,
    dataset_revision,
    validate_dataset_id,
)

DEADLINE = datetime(2026, 9, 11, 18, 0, tzinfo=UTC)


def make_version(**overrides: object) -> DatasetVersion:
    values: dict[str, object] = {
        "dataset_id": "KRX_DAILY_20260911",
        "data_deadline": DEADLINE,
        "collected_at": DEADLINE + timedelta(hours=1),
        "last_success_at": DEADLINE + timedelta(hours=1),
        "complete": True,
        "is_sample": False,
        "source": SOURCE_KRX_OPEN_API,
        "map_version_id": 1,
        "failure_reason": None,
    }
    values.update(overrides)
    return DatasetVersion(**values)  # type: ignore[arg-type]


class TestDatasetId:
    def test_build_from_date(self) -> None:
        assert build_dataset_id(date(2026, 9, 11)) == "KRX_DAILY_20260911"

    def test_build_from_datetime_uses_its_date(self) -> None:
        assert build_dataset_id(DEADLINE) == "KRX_DAILY_20260911"

    def test_build_rejects_other_types(self) -> None:
        with pytest.raises(InvalidDatasetIdError):
            build_dataset_id("2026-09-11")  # type: ignore[arg-type]

    def test_validate_accepts_expected_format(self) -> None:
        assert validate_dataset_id("KRX_DAILY_20260911") == "KRX_DAILY_20260911"


class TestRevision:
    """같은 마감일을 다시 발행하기 위한 판 번호 (research.md 결정 4의 2026-09-17 보완).

    발행본은 고칠 수 없으므로(FR-017) 같은 기간의 자료를 내용만 바꿔 다시 내려면 이름이 달라야
    한다. 마감일 하나로만 정하면 그 길이 없다.
    """

    def test_첫_판에는_판_번호를_붙이지_않는다(self) -> None:
        """붙이면 이미 발행된 이름들을 모두 바꿔야 한다."""
        assert build_dataset_id(date(2026, 9, 11)) == "KRX_DAILY_20260911"
        assert build_dataset_id(date(2026, 9, 11), revision=1) == "KRX_DAILY_20260911"

    def test_두_번째_판부터_번호가_붙는다(self) -> None:
        assert build_dataset_id(date(2026, 9, 11), revision=2) == "KRX_DAILY_20260911_R2"
        assert build_dataset_id(date(2026, 9, 11), revision=10) == "KRX_DAILY_20260911_R10"

    @pytest.mark.parametrize("revision", [0, -1, 1.5, True, "2"])
    def test_판_번호는_1_이상의_정수여야_한다(self, revision: object) -> None:
        with pytest.raises(InvalidDatasetIdError):
            build_dataset_id(date(2026, 9, 11), revision=revision)  # type: ignore[arg-type]

    def test_판_번호가_붙은_식별자를_받아들인다(self) -> None:
        assert validate_dataset_id("KRX_DAILY_20260911_R2") == "KRX_DAILY_20260911_R2"

    @pytest.mark.parametrize(
        "value",
        [
            "KRX_DAILY_20260911_R1",  # 첫 판은 번호를 붙이지 않으므로 _R1은 없는 표기다
            "KRX_DAILY_20260911_R0",
            "KRX_DAILY_20260911_R",
            "KRX_DAILY_20260911_r2",
            "KRX_DAILY_20260911_R2_R3",
        ],
    )
    def test_어긋난_판_표기를_거부한다(self, value: str) -> None:
        with pytest.raises(InvalidDatasetIdError):
            validate_dataset_id(value)

    def test_판_번호를_읽는다(self) -> None:
        assert dataset_revision("KRX_DAILY_20260911") == 1
        assert dataset_revision("KRX_DAILY_20260911_R2") == 2

    def test_판_번호는_마감일을_바꾸지_않는다(self) -> None:
        """판이 달라도 담은 구간의 끝은 같다. 다른 것은 내용이다."""
        assert dataset_deadline_date("KRX_DAILY_20260911_R2") == date(2026, 9, 11)

    def test_나중_판이_이름_정렬에서_뒤에_온다(self) -> None:
        """현재 판을 고르는 규칙이 '마감 시각이 같으면 식별자 내림차순'이라 이 성질에 기댄다."""
        assert "KRX_DAILY_20260911" < "KRX_DAILY_20260911_R2" < "KRX_DAILY_20260911_R3"

    @pytest.mark.parametrize(
        "value",
        [
            "KRX_DAILY_V1",  # 옛 표기. research.md 결정 4로 대체됐다
            "KRX_DAILY_2026091",
            "KRX_DAILY_202609110",
            "krx_daily_20260911",
            "KRX_DAILY_20260911 ",
            "KOSPI_DAILY_20260911",
            "KRX_DAILY_20260231",  # 달력에 없는 날짜
            "",
        ],
    )
    def test_validate_rejects_bad_values(self, value: str) -> None:
        with pytest.raises(InvalidDatasetIdError):
            validate_dataset_id(value)

    def test_validate_rejects_non_string(self) -> None:
        with pytest.raises(InvalidDatasetIdError):
            validate_dataset_id(20260911)

    def test_deadline_date_round_trip(self) -> None:
        assert dataset_deadline_date(build_dataset_id(DEADLINE)) == date(2026, 9, 11)


class TestPublishRules:
    def test_complete_version_needs_no_reason(self) -> None:
        version = make_version()
        assert version.complete is True
        assert version.failure_reason is None

    def test_incomplete_without_reason_is_rejected(self) -> None:
        with pytest.raises(IncompleteWithoutReasonError):
            make_version(complete=False, failure_reason=None)

    def test_incomplete_with_blank_reason_is_rejected(self) -> None:
        with pytest.raises(IncompleteWithoutReasonError):
            make_version(complete=False, failure_reason="   ")

    def test_incomplete_with_reason_is_accepted(self) -> None:
        version = make_version(complete=False, failure_reason="KRX OPEN API 이용 한도 도달")
        assert version.complete is False
        assert version.failure_reason == "KRX OPEN API 이용 한도 도달"

    @pytest.mark.parametrize("field", ["data_deadline", "collected_at", "last_success_at"])
    def test_naive_datetime_is_rejected(self, field: str) -> None:
        # 저장 열이 timestamptz라 시간대 없는 값은 어느 시각인지 정할 수 없다.
        with pytest.raises(DatasetVersionError):
            make_version(**{field: datetime(2026, 9, 11, 18, 0)})

    def test_blank_source_is_rejected(self) -> None:
        with pytest.raises(DatasetVersionError):
            make_version(source="  ")

    def test_source_longer_than_column_is_rejected(self) -> None:
        with pytest.raises(DatasetVersionError):
            make_version(source="A" * 65)

    @pytest.mark.parametrize("value", [0, -1, "1", True])
    def test_map_version_id_must_be_positive_int(self, value: object) -> None:
        with pytest.raises(DatasetVersionError):
            make_version(map_version_id=value)

    def test_version_is_frozen(self) -> None:
        version = make_version()
        with pytest.raises(Exception):  # noqa: B017 - dataclasses.FrozenInstanceError
            version.complete = False  # type: ignore[misc]


class TestRow:
    def test_row_columns_match_declared_columns(self) -> None:
        assert tuple(make_version().to_row()) == DATASET_VERSION_COLUMNS

    def test_row_carries_every_published_field(self) -> None:
        row = make_version(complete=False, failure_reason="부분 갱신", is_sample=True).to_row()
        assert row["dataset_id"] == "KRX_DAILY_20260911"
        assert row["data_deadline"] == DEADLINE
        assert row["complete"] is False
        assert row["failure_reason"] == "부분 갱신"
        assert row["is_sample"] is True
        assert row["source"] == SOURCE_KRX_OPEN_API
        assert row["map_version_id"] == 1

    def test_for_deadline_derives_dataset_id(self) -> None:
        version = DatasetVersion.for_deadline(
            DEADLINE,
            collected_at=DEADLINE,
            last_success_at=DEADLINE,
            complete=True,
            is_sample=False,
            source=SOURCE_KRX_OPEN_API,
            map_version_id=3,
        )
        assert version.dataset_id == "KRX_DAILY_20260911"
