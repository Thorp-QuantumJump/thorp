# Jira 101: executable momentum engine contract

`worker.engine.run_engine(engine_input, *, data_provider=None, on_snapshot=None) -> EngineOutput`
accepts `EngineInput` or a camelCase dictionary. `on_snapshot(ProgressSnapshot)` runs
synchronously once per completed evaluation trading day; its `portfolioSeries` contains
one new snapshot and counts are `completedTradingDays` / `totalTradingDays`. Consumer
owns batching (20 days plus the final remainder), messaging and persistence. Callback
exceptions propagate to the caller: publication failures must not be hidden as success.

Input parsing errors raise Pydantic validation errors. Data/engine failures return FAILED
EngineOutput with null performance/execution and `run.error`. Existing legacy models and
`run_backtest` remain separate and are not the new integration entry point.

The order-level audit log and the reproducibility metadata are NOT part of the message
contract. `NautilusMomentumEngine.run_with_artifacts` returns them alongside the message as
`RunArtifacts` (`worker/artifacts.py`); `run` returns the message only.

## Decisions overriding or clarifying the supplied design

- Single XKRX equity, MOMENTUM, default 120 trading days, initial KRW 10,000,000,
  max position 100%, zero commission AND tax, fixed 5 bps adverse slippage.
- `universe.instrumentIds` follows the detailed input table, not the `instruments` tree.
- Native market order is created at close (including quantity); gateway submits it only
  on the next available open quote. Bars do not execute orders. This avoids execution
  against the stale open or the current closing bar. The final order stays unsubmitted.
- Adjusted OHLC use yfinance `auto_adjust=True` (splits AND dividends); this is explicitly
  `TOTAL_RETURN_ADJUSTED`, not the design's example SPLIT_ADJUSTED. No ticker fallback.
- Entry/exit prices are actual fills; the pre-slippage opens stay internal to the engine and
  are recoverable from the fill price and slippageCost (per-order values are in the artifacts).
  grossPnl uses reference prices; realizedPnl = grossPnl - commission - slippageCost.
  returnRate divides by actual entryPrice * quantity. OPEN PnL fields are null.
- positions are present on first/last evaluation days and quantity-change days; absent
  otherwise. Absent differs from an empty (cash-only) snapshot. The positionValue sum
  invariant applies on days where positions are present; daily valuation still always runs.
- First dailyReturn is zero. CAGR uses elapsed calendar days between first and last
  observed evaluation days; zero elapsed days gives null. Sharpe includes first zero return.
- BacktestEngine uses an adjusted-price simulation instrument, not real KRX tick rounding.
  Fill prices are rounded to 6 decimals and simulation cash uses KRW_SIM (1:1 KRW) with
  8-decimal precision. Native KRW rounds to whole won, so its definition is not overwritten.
  Quote size is the maximum native quantity, covering every supported order size without
  imposing historical volume limits. This is explicitly a daily demo model.
- A cash-short order is rejected by the submission gateway before native submission, recorded
  in the artifacts order ledger, counted in execution.rejectedOrderCount, warned about in
  diagnostics, and cleared for next-close reevaluation. Accepted orders
  have native fill events; native fill count and account balance are reconciled to the output.
- No benchmark fields in EngineOutput (as required by the detailed EngineOutput section).
  Artifact metadata records source, ticker, identity, adjustment, dates and versions.
- Market halt/delisting event classification is unavailable in this temporary provider;
  missing sessions are skipped, with no claim to cause-based delisting support.

## Dependencies and references

Python 3.11; NautilusTrader 1.219.0; yfinance 0.2.65. The installed version's docstrings
and source are checked along with official documentation:

- https://nautilustrader.io/docs/latest/concepts/backtesting/execution-flow/
- https://nautilustrader.io/docs/latest/getting_started/installation/
- https://pypi.org/project/nautilus-trader/1.219.0/
- https://github.com/ranaroussi/yfinance/blob/0.2.65/yfinance/scrapers/history.py

The pinned history implementation documents exclusive end dates and applies the same
Adj Close / Close factor to Open, High, Low and Close. Adapter adds one day to inclusive
run.end, validates a single-symbol DataFrame, and records actual observed boundaries.
