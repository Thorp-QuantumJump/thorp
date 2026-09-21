"""구간 계산이 쓰는 원시 종가 읽기(spec 011 T016).

**시장 자료는 작업 메시지에 없다.** 백엔드는 004 실행 사본(자산·보유·체결)만 싣고, 평가액과
참고 지수에 필요한 원시 종가는 워커가 001이 적재한 표에서 직접 읽는다. 읽은 값은
``slicer.ClosePrices`` 모양으로 계산 쪽에 넘긴다 — ``slicer``는 pandas도 DB도 모른다.

백테스트 경로의 ``worker.market_data``를 부르지 않는다. 그쪽은 워밍업·달력·결측 검사까지 붙은
실행용 적재라 011이 필요로 하는 것(구간 며칠치 종가와 지수 종가)보다 훨씬 넓고, 지수 종가는
아예 돌려주지 않는다(달력으로 쓰고 버린다). 대신 표와 열 이름은 같은 곳
(``V20260911114500__etf_market_data.sql``)을 본다.

**없는 날을 채우지 않는다.** 행이 없으면 값이 없는 채로 넘기고, 필요한 날이 비었다는 판정은
``slicer``가 ``MARKET_DATA_MISSING``으로 한다(원칙 VI). 접속 실패 같은 일시 장애는 여기서
그대로 올라가 소비자가 ACK하지 않는 경로로 간다 — 결과로 바꾸면 일시 장애가 최종 실패로 굳는다.
"""

from collections.abc import Sequence
from datetime import date
from decimal import Decimal
from typing import Any, Protocol

from worker.regime_link.contracts import RegimeLinkJob
from worker.regime_link.slicer import ClosePrices, MappingClosePrices


class ClosePriceLoader(Protocol):
    """작업 하나가 필요로 하는 종가를 모아 온다. 소비자는 이 경계로만 자료를 만난다."""

    def load(self, job: RegimeLinkJob) -> ClosePrices: ...


def _as_decimal(value: Any) -> Decimal:
    """DB NUMERIC 은 이미 ``Decimal`` 이다. 다른 구현(가짜 커서·SQLite)이 float 을 주면
    문자열을 거쳐 바꾼다 — ``Decimal(float)`` 은 이진 오차를 그대로 끌고 온다."""
    if isinstance(value, Decimal):
        return value
    return Decimal(str(value))


class PostgresClosePriceLoader:
    """001이 적재한 ``daily_price``·``daily_index`` 를 읽는 운영 구현(읽기 전용).

    종목 종가는 ``daily_price.close`` 다 — 수정 종가가 아니다. 011의 평가액은 그날 실제 가격 ×
    그날 수량이고 수량 이월이 분할을 이미 반영하므로(``dailyPositions`` 덮어쓰기), 여기서
    수정 종가를 쓰면 분할일 앞뒤 평가액이 두 번 조정된다.

    참고 지수는 종목 표에 없다. ``benchmarkInstrumentId``(예: ``KOSPI``)는 ``daily_index`` 의
    ``index_code`` 이며, 같은 사전에 그 이름 그대로 담아 ``slicer`` 가 종목과 같은 방식으로
    읽게 한다.
    """

    def __init__(self, url: str, *, connect: Any = None) -> None:
        self.url = url
        # psycopg 를 생성자에서 import 하지 않는다(테스트가 가짜 접속을 주입한다).
        self._connect = connect

    def _open(self) -> Any:
        if self._connect is not None:
            return self._connect(self.url)
        import psycopg

        connection = psycopg.connect(self.url)
        connection.read_only = True
        return connection

    def _instrument_closes(
        self,
        cursor: Any,
        dataset_id: str,
        instrument_ids: Sequence[str],
        first: date,
        last: date,
    ) -> dict[str, dict[date, Decimal]]:
        cursor.execute(
            "SELECT instrument_id, date, close FROM daily_price"
            " WHERE dataset_id = %s AND instrument_id = ANY(%s)"
            " AND date BETWEEN %s AND %s"
            " ORDER BY instrument_id, date",
            (dataset_id, list(instrument_ids), first, last),
        )
        closes: dict[str, dict[date, Decimal]] = {iid: {} for iid in instrument_ids}
        for record in cursor.fetchall():
            closes.setdefault(record[0], {})[record[1]] = _as_decimal(record[2])
        return closes

    def _index_closes(
        self, cursor: Any, dataset_id: str, index_code: str, first: date, last: date
    ) -> dict[date, Decimal]:
        cursor.execute(
            "SELECT date, close FROM daily_index"
            " WHERE dataset_id = %s AND index_code = %s AND date BETWEEN %s AND %s"
            " ORDER BY date",
            (dataset_id, index_code, first, last),
        )
        return {record[0]: _as_decimal(record[1]) for record in cursor.fetchall()}

    def load(self, job: RegimeLinkJob) -> MappingClosePrices:
        """``baselineDate`` ~ ``lastAvailable`` 의 종가를 한 번에 읽는다.

        구간 계산이 보는 가장 이른 날이 ``baselineDate``(연속 운용의 기준 평가액·지수 기준값)이고
        가장 늦은 날이 ``lastAvailable`` 이다. ``d63`` 이 더 뒤여도 그 뒤는 읽지 않는다 —
        백엔드가 폐지·미완성을 이미 ``lastAvailable`` 로 줄여 보냈다.
        """
        instrument_ids = sorted({item.instrument_id for item in job.universe.items})
        dataset_id = job.market_data.dataset_id
        first, last = job.window.baseline_date, job.window.last_available
        benchmark = job.market_data.benchmark_instrument_id
        with self._open() as connection, connection.cursor() as cursor:
            closes = self._instrument_closes(cursor, dataset_id, instrument_ids, first, last)
            closes[benchmark] = self._index_closes(cursor, dataset_id, benchmark, first, last)
        return MappingClosePrices(closes)
