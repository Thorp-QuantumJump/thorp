"""실행 Consumer와 엔진의 경계. 엔진 담당은 ``BacktestEngine`` 만 구현한다.

- 엔진은 DB·브로커를 모른다. ``EngineInput`` → ``EngineOutput`` 만 책임진다.
- 진행 콜백은 **거래일 1개 단위**. 20거래일 묶음 PROGRESS 발행, 벤치마크 계산,
  STARTED/FINISHED 발행, ACK는 실행 Consumer(Task 4a)가 한다.
  실행 Consumer는 EngineOutput 내용을 해석하지 않는다.
- 실패는 예외가 아니라 ``EngineOutput(run.status=FAILED, run.error=...)`` 로 반환한다 (README §4.3).

기존 ``worker/engine.py`` 모듈과 이름이 겹치므로 ``worker/engine/`` 패키지는 만들지 않는다.
그 모듈의 정리는 Task 4a.
"""

from typing import Protocol, runtime_checkable

from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import EngineOutput
from worker.contracts.events import ProgressPoint


class ProgressCallback(Protocol):
    def __call__(
        self, point: ProgressPoint, completed_trading_days: int, total_trading_days: int
    ) -> None: ...


@runtime_checkable
class BacktestEngine(Protocol):
    def run(self, engine_input: EngineInput, on_progress: ProgressCallback) -> EngineOutput: ...
