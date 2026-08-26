/** 백엔드 BacktestJobRequest / 응답과 맞추는 타입. 스펙 확정 시 갱신한다. */
export type BacktestStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface BacktestJobRequest {
  strategy: string
  startDate: string // YYYY-MM-DD
  endDate: string
}

export interface BacktestSummary {
  backtestId: number
  status: BacktestStatus
}
