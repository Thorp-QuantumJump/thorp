export type StrategyId = 'trend' | 'dualMomentum' | 'rsiMeanReversion'

export interface StrategyResult {
  id: StrategyId
  name: string
  description: string
  parameter: string
  color: string
  mutedColor: string
  totalReturn: string
  cagr: string
  mdd: string
  sharpe: string
  volatility: string
  trades: number
}

export interface BacktestPoint extends Record<string, unknown> {
  date: Date
  trend: number
  dualMomentum: number
  rsiMeanReversion: number
  benchmark: number
}

// 전략 선택과 결과 비교 UI에서 함께 사용하는 고정 성과 데이터다.
export const strategyResults: StrategyResult[] = [
  {
    id: 'trend',
    name: '이동평균 추세추종',
    description: '중기 추세에 올라타고 하락 구간의 노출을 줄입니다.',
    parameter: 'MA 20 / 60 · 월 리밸런싱',
    color: '#18a7a0',
    mutedColor: 'rgb(24 167 160 / 18%)',
    totalReturn: '+124.8%',
    cagr: '18.4%',
    mdd: '-9.1%',
    sharpe: '1.42',
    volatility: '12.4%',
    trades: 156,
  },
  {
    id: 'dualMomentum',
    name: '듀얼 모멘텀',
    description: '상대·절대 모멘텀을 함께 사용해 강한 자산을 선택합니다.',
    parameter: '12개월 모멘텀 · 월 리밸런싱',
    color: '#f19a24',
    mutedColor: 'rgb(241 154 36 / 18%)',
    totalReturn: '+84.6%',
    cagr: '13.2%',
    mdd: '-12.1%',
    sharpe: '1.05',
    volatility: '14.2%',
    trades: 89,
  },
  {
    id: 'rsiMeanReversion',
    name: 'RSI 평균회귀',
    description: '단기 과매도 구간의 가격 정상화를 포착합니다.',
    parameter: 'RSI 14 · 매수 30 / 매도 70',
    color: '#8c5ee8',
    mutedColor: 'rgb(140 94 232 / 18%)',
    totalReturn: '+62.4%',
    cagr: '10.8%',
    mdd: '-15.8%',
    sharpe: '0.78',
    volatility: '18.1%',
    trades: 234,
  },
]

// 한 틱씩 그려지는 차트 연출을 확인하기 위한 시계열 원본 데이터다.
const rawPoints = [
  ['2019-01-01', 100, 100, 100, 100],
  ['2019-04-01', 106, 102, 104, 105],
  ['2019-07-01', 112, 105, 110, 108],
  ['2019-10-01', 108, 103, 116, 111],
  ['2020-01-01', 119, 111, 111, 116],
  ['2020-04-01', 104, 98, 108, 94],
  ['2020-07-01', 126, 116, 122, 120],
  ['2020-10-01', 138, 124, 130, 132],
  ['2021-01-01', 149, 132, 137, 143],
  ['2021-04-01', 143, 127, 134, 147],
  ['2021-07-01', 164, 138, 145, 153],
  ['2021-10-01', 178, 145, 151, 160],
  ['2022-01-01', 169, 137, 145, 143],
  ['2022-04-01', 158, 129, 139, 127],
  ['2022-07-01', 176, 142, 151, 136],
  ['2022-10-01', 183, 148, 157, 142],
  ['2023-01-01', 196, 154, 162, 151],
  ['2023-04-01', 188, 149, 158, 158],
  ['2023-07-01', 210, 167, 169, 171],
  ['2023-10-01', 224.8, 184.6, 162.4, 180.2],
] as const

// 문자열 날짜와 전략별 값을 Bklit 차트가 사용하는 데이터 형태로 변환한다.
export const backtestChartData: BacktestPoint[] = rawPoints.map(
  ([date, trend, dualMomentum, rsiMeanReversion, benchmark]) => ({
    date: new Date(date),
    trend,
    dualMomentum,
    rsiMeanReversion,
    benchmark,
  }),
)
