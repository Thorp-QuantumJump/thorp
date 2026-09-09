# Jira 101: executable momentum engine contract

`worker.engine_runner.NautilusMomentumEngine(data_provider=None).run(engine_input, on_progress)
-> EngineOutput` accepts `EngineInput` or a camelCase dictionary. The market data provider is a
constructor argument, so one engine instance can serve many runs. The callback follows the
message contract
`contracts.engine.ProgressCallback`: `on_progress(point, completed_trading_days,
total_trading_days)` runs synchronously once per completed evaluation trading day, where
`point` is a `ProgressPoint(date, equity, cumulativeReturn, drawdown)` whose values match the
same-date entry of the final `portfolioSeries`. Consumer owns batching (20 days plus the final
remainder), messaging and persistence. Progress events are streamed over SSE and never stored,
so the callback deliberately omits the per-day position list; the full snapshots stay in the
final `portfolioSeries`. Callback exceptions propagate to the caller: publication failures must
not be hidden as success.

Input parsing errors raise Pydantic validation errors. Data/engine failures return FAILED
EngineOutput with null performance/execution and `run.error`. The legacy Kafka models and
`run_backtest` were deleted when the pika execution consumer landed (`worker/consumer.py`,
`worker/rabbitmq.py`); `python -m worker.main` is now that consumer's entry point.

The order-level audit log and the reproducibility metadata are NOT part of the message
contract. `NautilusMomentumEngine.run_with_artifacts` returns them alongside the message as
`RunArtifacts` (`worker/artifacts.py`); `run` returns the message only.

Input, result and progress models are all the shared message contract: `EngineInput` comes from
`worker/contracts/engine_input.py`, `EngineOutput` from `worker/contracts/engine_output.py` and
the callback point from `worker/contracts/events.py`. The transitional input model
`worker/engine_contracts.py` has been deleted; the last two disagreements were resolved by
adding the `ZERO` commission model to the contract (ADR-0004) and by accepting
`delisting=CAUSE_BASED` with a warning (ADR-0003 "D-상장폐지").

The contract deliberately admits values this engine cannot compute, so requests outside the
engine's supported range are rejected before execution as
`EngineOutput(run.status=FAILED, run.error.code=INVALID_REQUEST)` by
`worker/engine_support.py`. That check lives in the engine, not in `contracts/`: the contract is
shared with the backend and the structural fixture
`docs/specs/backtest-messaging/examples/engine-input.json` must keep passing it.

## Decisions overriding or clarifying the supplied design

- Single XKRX equity, MOMENTUM, default 120 trading days, initial KRW 10,000,000,
  max position 100%, zero commission AND tax, fixed 5 bps adverse slippage. The contract
  fixes `execution.commission` to the KIWOOM broker schedule instead; ADR-0003 records why
  that gap blocks the input model from moving to the contract.
- `universe.instrumentIds` follows the detailed input table, not the `instruments` tree.
- Native market order is created at close (including quantity); gateway submits it only
  on the next available open quote. Bars do not execute orders. This avoids execution
  against the stale open or the current closing bar. The final order stays unsubmitted.
- OHLC use yfinance `auto_adjust=False`, i.e. Yahoo's raw series, which reflects splits only.
  That is exactly the `SPLIT_ADJUSTED` the messaging contract fixes for
  `marketData.adjustment`, so the engine and the contract agree on this field; pykrx's
  adjusted prices carry the same meaning, so a later switch to KRX data needs no contract
  change. Dividend reinvestment is therefore excluded and live demo returns are lower than
  the earlier `auto_adjust=True` (`TOTAL_RETURN_ADJUSTED`) runs — an approved trade-off that
  also makes runs more stable, because dividend adjustment retroactively rewrites past prices
  on every new dividend. No ticker fallback. See ADR-0003 "메시지 계약과의 정합 상태".
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
- No benchmark fields in EngineOutput (as required by the detailed EngineOutput section);
  the contract's `BenchmarkResult` is computed by the consumer and joined into the FINISHED
  payload, not produced here. Reproducibility metadata (source, ticker, identity, adjustment,
  observed dates, versions) lives in `RunArtifacts.metadata`, outside the message.
- Market halt/delisting event classification is unavailable in this temporary provider;
  missing sessions are skipped, with no claim to cause-based delisting support. The contract
  fixes `execution.delisting=CAUSE_BASED`; see ADR-0003 for that gap.

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
