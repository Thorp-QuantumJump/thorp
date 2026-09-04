export type StrategyId =
  | 'trend'
  | 'dualMomentum'
  | 'rsiMeanReversion'
  | 'bollingerBands'
  | 'lowVolatility'

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
  bollingerBands: number
  lowVolatility: number
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
  {
    id: 'bollingerBands',
    name: '볼린저 밴드',
    description: '밴드 하단 이탈 후 복귀하는 가격 움직임을 살펴봅니다.',
    parameter: '기간 20 · 표준편차 2 · 중심선 청산',
    color: '#4779b5',
    mutedColor: 'rgb(71 121 181 / 18%)',
    totalReturn: '+73.2%',
    cagr: '11.6%',
    mdd: '-14.3%',
    sharpe: '0.91',
    volatility: '16.2%',
    trades: 182,
  },
  {
    id: 'lowVolatility',
    name: '저변동성',
    description: '최근 가격 변동이 작은 종목을 골라 분산 투자합니다.',
    parameter: '변동성 60일 · 하위 20% · 월 리밸런싱',
    color: '#a57561',
    mutedColor: 'rgb(165 117 97 / 18%)',
    totalReturn: '+48.6%',
    cagr: '8.2%',
    mdd: '-7.4%',
    sharpe: '1.12',
    volatility: '8.6%',
    trades: 64,
  },
]

// 한 틱씩 그려지는 차트 연출을 확인하기 위한 시계열 원본 데이터다.
const rawPoints = [
  ['2019-01-01', 100, 100, 100, 100, 100, 100],
  ['2019-04-01', 106, 102, 104, 105, 103, 102],
  ['2019-07-01', 112, 105, 110, 108, 109, 105],
  ['2019-10-01', 108, 103, 116, 111, 106, 107],
  ['2020-01-01', 119, 111, 111, 116, 114, 111],
  ['2020-04-01', 104, 98, 108, 94, 97.7, 103],
  ['2020-07-01', 126, 116, 122, 120, 118, 112],
  ['2020-10-01', 138, 124, 130, 132, 128, 117],
  ['2021-01-01', 149, 132, 137, 143, 135, 121],
  ['2021-04-01', 143, 127, 134, 147, 130, 123],
  ['2021-07-01', 164, 138, 145, 153, 142, 128],
  ['2021-10-01', 178, 145, 151, 160, 149, 132],
  ['2022-01-01', 169, 137, 145, 143, 138, 127],
  ['2022-04-01', 158, 129, 139, 127, 129, 122.2],
  ['2022-07-01', 176, 142, 151, 136, 144, 129],
  ['2022-10-01', 183, 148, 157, 142, 152, 133],
  ['2023-01-01', 196, 154, 162, 151, 158, 138],
  ['2023-04-01', 188, 149, 158, 158, 151, 140],
  ['2023-07-01', 210, 167, 169, 171, 165, 144],
  ['2023-10-01', 224.8, 184.6, 162.4, 180.2, 173.2, 148.6],
] as const

// 문자열 날짜와 전략별 값을 Bklit 차트가 사용하는 데이터 형태로 변환한다.
export const backtestChartData: BacktestPoint[] = rawPoints.map(
  ([date, trend, dualMomentum, rsiMeanReversion, benchmark, bollingerBands, lowVolatility]) => ({
    date: new Date(date),
    trend,
    dualMomentum,
    rsiMeanReversion,
    benchmark,
    bollingerBands,
    lowVolatility,
  }),
)
