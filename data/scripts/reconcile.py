"""공시 대조 실행 (quickstart 시나리오 5, spec 001 FR-016·FR-017·SC-002).

사용 예::

    python scripts/reconcile.py --dataset KRX_DAILY_20260907 \
        --listing raw/listing.json --corporate-actions raw/actions.json \
        --disclosure raw/disclosure.json --definition-diff-fields close

표본은 상장 첫날·모든 분배금 **지급일**·모든 분할일이다. 평범한 거래일은 넣지 않는다(FR-016).
불일치가 나와도 개별 값을 고치지 않고 원인에 따라 처리가 갈린다(FR-017).

입력 파일 세 개는 모두 JSON 배열이며, 열 이름은 ``data_pipeline``의 ``*_from_records``
계약과 같은 이름(``instrument_id``·``ex_date``·``pay_date``…)을 쓴다. 이름을 camelCase로
바꾸면 변환을 한 겹 더 두어야 하고 그 겹이 조용히 열을 흘릴 수 있다.

``--listing``::

    [{"instrument_id": "069500.XKRX", "listed_on": "2002-10-14"}]

``--corporate-actions``::

    [{"instrument_id": "069500.XKRX", "ex_date": "2026-04-29",
      "pay_date": "2026-05-08", "event_type": "DISTRIBUTION", "amount": "120"}]

``--disclosure`` (운용사 공시를 사람이 옮긴 파일. 공시는 API로 받을 수 없다)::

    [{"instrument_id": "069500.XKRX", "target_date": "2026-05-08",
      "sample_reason": "DISTRIBUTION", "event_ex_date": "2026-04-29",
      "values": {"amount": "118", "pay_date": "2026-05-08", "ex_date": "2026-04-29"}}]

``event_ex_date``는 **표본 구분자**이며 상장 첫날 표본에는 없다. 지급일이 같고 배당락일이
다른 분배금이 둘인 종목에서 어느 쪽의 공시값인지 가린다. 이 칸이 없으면 배당락일이 다른
분배금 둘이 한 표본으로 합쳐져, 금액까지 같으면 기업행위 하나가 대조되지 않은 채 "표본을
모두 대조했다"는 판정이 나온다(FR-016은 "모든 분배금 지급일"을 요구한다).

``values``의 항목 이름은 조회값의 이름과 같아야 한다. 분배금은 ``amount``·``pay_date``·
``ex_date`` 셋을 대조한다. 배당락일까지 대조하는 이유는 그 날짜가 틀리면 조정 계수가 엉뚱한
구간에 곱해지는데(research.md 결정 2), 금액만 맞춰 보면 그 오류가 드러나지 않기 때문이다.
이름이 빠지면 그 항목은 "대조하지 않음"으로 남고 SC-002 판정이 미충족이 된다.

조회값은 ``--listing``·``--corporate-actions``에서 뽑는다. 이 파트의 적재 방향은
``raw → processed → PostgreSQL`` 한 방향뿐이라 DB에서 읽어 되돌리지 않는다.

종료 코드: ``0`` 정상, ``1`` 재발행이 필요한 상태(``PIPELINE_ERROR``가 있다), ``2`` 표본이
빠짐없이 대조되지 않아 SC-002를 충족하지 못한 상태. 재발행이 필요한 채로 성공 코드를 내면 그
자료 버전이 그대로 쓰이게 되므로 0으로 끝내지 않는다.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Mapping, Sequence
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

# ``pyproject.toml``에 build-system이 없어 이 파트는 설치되는 꾸러미가 아니다. 그래서 pytest는
# ``pythonpath = ["src"]``로 읽지만 셸에서 이 스크립트를 그냥 실행하면 ``data_pipeline``을 찾지
# 못한다. 진입점에서 경로를 붙여 두면 실행 방법에 따라 결과가 달라지지 않는다.
_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from data_pipeline.corporate_actions import corporate_actions_from_records  # noqa: E402
from data_pipeline.reconciliation import (  # noqa: E402
    ReconciliationOutcome,
    Sc002Verdict,
    build_queried_values,
    build_samples,
    disclosed_values_from_records,
    listing_windows_from_records,
    reconcile,
    sc002_verdict,
)
from data_pipeline.repository import MarketDataRepository  # noqa: E402

#: 파일에서 문자열로 오는 날짜 열. ``date``로 바꿔 ``*_from_records``에 넘긴다.
#: JSON 입력에서 날짜로 바꿀 열. ``event_ex_date``는 표본 구분자이며(지급일이 같은 분배금이
#: 둘인 종목에서 어느 쪽의 공시값인지 가린다) 여기 없으면 문자열로 남아 거부된다.
_DATE_FIELDS: tuple[str, ...] = (
    "listed_on",
    "delisted_on",
    "ex_date",
    "pay_date",
    "target_date",
    "event_ex_date",
)


def _read_records(path: Path, label: str) -> list[Mapping[str, Any]]:
    """JSON 배열을 읽고 날짜 문자열을 ``date``로 바꾼다."""
    if not path.exists():
        raise SystemExit(f"{label} 파일이 없다: {path}")
    loaded = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(loaded, list):
        raise SystemExit(f"{label} 파일은 JSON 배열이어야 한다: {path}")
    records: list[Mapping[str, Any]] = []
    for position, record in enumerate(loaded):
        if not isinstance(record, dict):
            raise SystemExit(f"{label} {position}번째 항목이 객체가 아니다: {path}")
        records.append(
            {
                name: _parse_date(value, f"{label}[{position}].{name}")
                if name in _DATE_FIELDS and value is not None
                else value
                for name, value in record.items()
            }
        )
    return records


def _parse_date(value: object, where: str) -> date:
    if isinstance(value, str):
        try:
            return date.fromisoformat(value)
        except ValueError as error:
            raise SystemExit(f"{where}의 날짜를 읽을 수 없다: {value!r}") from error
    raise SystemExit(f"{where}는 YYYY-MM-DD 문자열이어야 한다: {value!r}")


def _report(outcome: ReconciliationOutcome, verdict: Sc002Verdict, dataset_id: str) -> None:
    """사람이 읽을 결과를 낸다. 숫자를 줄이지 않고 그대로 적는다."""
    print(f"자료 버전: {dataset_id}")
    print("")
    print("표본 구성 (FR-016: 상장 첫날·모든 분배금 지급일·모든 분할일)")
    for reason, count in outcome.sample_counts.items():
        print(f"  {reason:<14}{count}건")
    print(f"  {'합계':<13}{verdict.sample_count}건")
    print("")
    print("대조 결과 (표본의 항목 단위)")
    print(f"  대조한 항목   {verdict.reconciled_field_count}건")
    print(f"  일치          {verdict.matched}건")
    print(f"  불일치        {verdict.mismatched}건")
    for cause, count in verdict.cause_counts.items():
        print(f"    {cause:<16}{count}건")
    print(f"  대조하지 않은 표본  {verdict.unreconciled_sample_count}건")
    for item in outcome.unreconciled:
        fields = f" 항목={list(item.fields)}" if item.fields else ""
        print(
            f"    {item.target.instrument_id} {item.target.target_date} "
            f"{item.target.sample_reason}: {item.reason}{fields}"
        )
    print("")
    print("처리 (FR-017: 개별 값은 고치지 않는다)")
    print(f"  새 자료 버전 재발행 필요  {len(outcome.republish_required)}건")
    for record in outcome.republish_required:
        print(
            f"    {record.instrument_id} {record.target_date} {record.field_name}: "
            f"{record.difference}"
        )
    print(f"  구간 제외(data_gap RECONCILIATION_UNKNOWN)  {len(outcome.excluded_ranges)}행")
    for row in outcome.excluded_ranges:
        print(f"    {row['instrument_id']} {row['start_date']} ~ {row['end_date']}")
    print("")
    print(f"SC-002: {'충족' if verdict.satisfied else '미충족'}: {verdict.detail}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="대표 ETF 공시 대조 (FR-016·FR-017)")
    parser.add_argument("--dataset", required=True, help="자료 버전 식별자 (예 KRX_DAILY_20260907)")
    parser.add_argument("--disclosure", required=True, help="운용사 공시값 JSON 파일 경로")
    parser.add_argument("--listing", required=True, help="상장 구간 JSON 파일 경로")
    parser.add_argument(
        "--corporate-actions",
        required=True,
        help="기업행위 JSON 파일 경로. 빼면 분배금·분할 표본이 조용히 사라진다",
    )
    parser.add_argument(
        "--definition-diff-fields",
        nargs="*",
        default=[],
        metavar="FIELD",
        help="값의 정의가 달라 생긴 차이로 볼 항목 (기록만 남긴다)",
    )
    parser.add_argument(
        "--pipeline-error-fields",
        nargs="*",
        default=[],
        metavar="FIELD",
        help="수집·가공 오류로 볼 항목 (새 자료 버전으로 재발행한다)",
    )
    parser.add_argument(
        "--commit",
        action="store_true",
        help="대조 기록을 저장소에 적재한다. 없으면 표본과 판정을 출력만 한다",
    )
    parser.add_argument("--env-file", help="DATABASE_URL이 든 환경 파일 경로")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    windows = listing_windows_from_records(_read_records(Path(args.listing), "--listing"))
    actions = corporate_actions_from_records(
        _read_records(Path(args.corporate_actions), "--corporate-actions")
    )
    disclosed = disclosed_values_from_records(_read_records(Path(args.disclosure), "--disclosure"))

    targets = build_samples(windows, actions)
    queried = build_queried_values(windows, actions)
    outcome = reconcile(
        targets,
        queried,
        disclosed,
        definition_diff_fields=args.definition_diff_fields,
        pipeline_error_fields=args.pipeline_error_fields,
        checked_at=datetime.now(UTC),
    )
    verdict = sc002_verdict(outcome)
    _report(outcome, verdict, args.dataset)

    # 표본에 없는 날의 공시값은 대조에 쓰이지 않는다. 조용히 버리면 옮겨 적은 사람이 그 사실을
    # 모르고 "대조했다"고 여긴다 (평범한 거래일은 표본이 아니다, FR-016).
    unused = sorted(set(disclosed) - {target.key for target in targets})
    if unused:
        print("")
        print(f"표본에 없어 쓰지 않은 공시값 {len(unused)}건")
        for instrument_id, target_date, sample_reason, event_ex_date in unused:
            # 배당락일까지 낸다. 지급일이 같은 분배금이 둘인 종목에서는 그 값이 어긋나
            # 짝이 맞지 않은 경우가 있어, 날짜만 보면 왜 안 쓰였는지 알 수 없다.
            suffix = "" if event_ex_date is None else f" (배당락일 {event_ex_date})"
            print(f"  {instrument_id} {target_date} {sample_reason}{suffix}")

    if args.commit:
        env_path = Path(args.env_file) if args.env_file else None
        # 접속 문자열은 반드시 database_url_from_env를 거친다. `.env`의 postgresql://을
        # SQLAlchemy가 psycopg2로 해석하는데 이 파트에 설치된 것은 psycopg 3뿐이다.
        repository = MarketDataRepository.from_env(env_path)
        written = repository.load_reconciliation_records(args.dataset, outcome.records)
        print(f"적재: reconciliation_record {written}행")
        if outcome.excluded_ranges:
            # data_gap 재적재는 FR-017에 따라 거부되므로 여기서 넣지 않는다. 구간 제외는
            # 그 자료 버전의 data_gap을 적재할 때나 재발행본에 함께 담아야 한다.
            print(
                f"적재하지 않음: data_gap {len(outcome.excluded_ranges)}행. 발행된 자료 버전의 "
                "data_gap은 다시 적재할 수 없다. 위 구간을 자료 부족 구간 적재나 재발행본에 담는다"
            )
    else:
        print("적재: 건너뜀 (--commit이 없다)")

    if outcome.republish_required:
        print(
            "재발행 필요: 수집·가공을 바로잡아 새 dataset_id로 다시 발행해야 한다. "
            "개별 값을 고치는 경로는 없다 (FR-017)"
        )
        return 1
    if not verdict.satisfied:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
