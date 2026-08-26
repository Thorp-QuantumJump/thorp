"""KRX 데이터 수집 스크립트 (스캐폴딩).

사용 예:
    python scripts/ingest_krx.py --start 2021-01-01 --end 2026-08-26 --tickers 005930
"""

import argparse


def main() -> None:
    parser = argparse.ArgumentParser(description="KRX 일봉 수집")
    parser.add_argument("--start", required=True, help="YYYY-MM-DD")
    parser.add_argument("--end", required=True, help="YYYY-MM-DD")
    parser.add_argument("--tickers", nargs="*", default=["005930"], help="종목코드 목록")
    args = parser.parse_args()

    # TODO: data_pipeline.krx.fetch_ohlcv 호출 -> data/raw/ 에 Parquet 저장
    print(f"[TODO] {args.start} ~ {args.end}, tickers={args.tickers}")


if __name__ == "__main__":
    main()
