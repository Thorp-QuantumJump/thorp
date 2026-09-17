import type { RegistryResponse, StrategyView } from '@/types/strategyRegistry'

/**
 * 003 화면 테스트용 등록표 응답 픽스처.
 *
 * <p><b>등록표에 없는 전략과 축을 일부러 쓴다.</b> 실제 전략 이름으로 픽스처를 만들면 화면이 그
 * 전략을 알고 있어도 테스트가 통과한다 — 이름을 지어내야 "응답만 보고 그린다"가 증명된다
 * (FR-005). 픽스처에 전략 데이터를 두는 것은 그 규칙의 명시적 예외이며,
 * `src/test/paramSearchFixtures.ts`가 007에서 같은 이유로 같은 방식을 쓴다.
 *
 * <p>아래 숫자와 버전 문자열은 계약 형식을 보이려는 합성 값이다. 제품의 후보값·범위가 아니며
 * 운영 등록표의 대체값으로 쓰지 않는다.
 */

/** 고를 수 있는 전략. 목록형 축과 입력형 축을 하나씩 갖고 워밍업 규칙은 축 하나를 더한다. */
export const selectableStrategy: StrategyView = {
  strategyId: 'DRIFT_BALANCE',
  name: '표류 균형 맞추기',
  description: '값이 멀어진 쪽을 되돌리는 방식으로 나눈다.',
  targetWeightSource: 'COMPUTED',
  selectable: true,
  warmupTradingDays: 45,
  axes: [
    {
      name: 'driftWindow',
      label: '표류 관측 창',
      description: '값이 얼마나 멀어졌는지 재는 데 쓸 과거 구간의 길이다.',
      unit: '거래일',
      inputType: 'CHOICE',
      choices: [
        { value: 15, label: '15거래일', selectable: true },
        { value: 45, label: '45거래일', selectable: true },
        { value: 90, label: '90거래일', selectable: true },
      ],
      defaultValue: 45,
    },
    {
      name: 'resetTiming',
      label: '되돌리는 시점',
      description: '벌어진 비중을 언제 되돌릴지 정한다.',
      // 합성 값을 갖는 축이다. 실제 등록표의 리밸런싱 주기가 이 형태이며(data-model §5),
      // 화면이 칸 이름의 뜻을 읽지 않고 `label`만 쓴다는 것을 이 픽스처가 고정한다.
      inputType: 'CHOICE',
      choices: [
        { value: { kind: 'PERIOD_END', unit: 'MONTH' }, label: '달 끝마다', selectable: true },
        { value: { kind: 'FIXED_DAYS', span: 30 }, label: '30거래일마다', selectable: true },
      ],
      defaultValue: { kind: 'PERIOD_END', unit: 'MONTH' },
    },
    {
      name: 'bandWidth',
      label: '허용 이탈 폭',
      description: '이만큼 벌어지기 전까지는 되돌리지 않는다.',
      unit: '퍼센트',
      inputType: 'NUMBER',
      range: { min: 5, max: 50, step: 5 },
      // 섹터 셋에서도 기본 설정이 규칙(× 섹터 수 < 100이면 위반)을 지키는 값이다(35 × 3 = 105). 카드가
      // `selectable: true`인데 기본값이 규칙을 어기면, 서버가 내리지 않을 조합이라 화면 검사가 거짓 전제에
      // 선다 — 페이지는 규칙 위반이면 '분석하기'를 막는다(T058).
      defaultValue: 35,
    },
  ],
  validationRules: [
    {
      code: 'BAND_TOO_NARROW_FOR_UNIVERSE',
      detail: '허용 이탈 폭이 좁아 후보 섹터만으로 전액을 배분할 수 없습니다.',
      left: { axis: 'bandWidth', multiplyByContext: 'UNIVERSE_SIZE' },
      operator: 'LESS_THAN',
      right: { value: 100 },
    },
  ],
  warmupRule: { sum: ['driftWindow'] },
}

/**
 * 지금 조건에서는 고를 수 없는 전략.
 *
 * <p>기본 설정(`pickCount: 4`)이 섹터 셋이라는 조건에서 규칙을 어기므로 카드가 `selectable: false`이고,
 * 같은 이유로 그 축의 후보 `4`도 `selectable: false`이되 <b>목록에 남는다</b>. 워밍업 규칙은 축 둘을
 * 더한다.
 */
export const unselectableStrategy: StrategyView = {
  strategyId: 'PULSE_ROTATION',
  name: '맥박 따라 옮기기',
  description: '오름세가 뚜렷한 쪽으로 자리를 옮긴다.',
  targetWeightSource: 'USER',
  selectable: false,
  unselectableReason: '고를 섹터 수가 후보 섹터 집합의 크기보다 큽니다.',
  warmupTradingDays: 70,
  axes: [
    {
      name: 'pulseWindow',
      label: '맥박 관측 창',
      description: '오름세를 재는 데 쓸 과거 구간의 길이다.',
      unit: '거래일',
      inputType: 'CHOICE',
      choices: [
        { value: 30, label: '30거래일', selectable: true },
        { value: 60, label: '60거래일', selectable: true },
      ],
      defaultValue: 60,
    },
    {
      name: 'coolDownDays',
      label: '직전 구간 제외 기간',
      description: '관측 구간의 맨 끝을 이만큼 빼고 본다.',
      unit: '거래일',
      inputType: 'CHOICE',
      choices: [
        { value: 0, label: '0거래일', selectable: true },
        { value: 10, label: '10거래일', selectable: true },
      ],
      defaultValue: 10,
    },
    {
      name: 'pickCount',
      label: '고를 섹터 수',
      description: '오름세가 앞선 섹터를 몇 개까지 담을지 정한다.',
      inputType: 'CHOICE',
      choices: [
        { value: 2, label: '2', selectable: true },
        { value: 3, label: '3', selectable: true },
        { value: 4, label: '4', selectable: false },
      ],
      defaultValue: 4,
    },
  ],
  validationRules: [
    {
      code: 'PICK_COUNT_EXCEEDS_UNIVERSE',
      detail: '고를 섹터 수가 후보 섹터 집합의 크기보다 큽니다.',
      left: { axis: 'pickCount' },
      operator: 'GREATER_THAN',
      right: { context: 'UNIVERSE_SIZE' },
    },
  ],
  warmupRule: { sum: ['pulseWindow', 'coolDownDays'] },
}

/** 워밍업도 설정 항목도 없는 전략. 값이 없는 필드를 `null`로 싣지 않는다는 것을 함께 고정한다. */
export const plainStrategy: StrategyView = {
  strategyId: 'EVEN_SPLIT_TEST',
  name: '똑같이 나누기(시험용)',
  description: '고른 섹터에 같은 몫을 둔다.',
  targetWeightSource: 'EQUAL',
  selectable: true,
  warmupTradingDays: 0,
  axes: [],
  validationRules: [],
}

/**
 * 조건부 축과 용어 식별자를 가진 전략(계약 §1 `appliesWhen`·`termId`).
 *
 * <p>`gate`가 `LEFT`면 `leftGap`만, `RIGHT`면 `rightPick`만 뜻이 있다. 실제 등록표의 선정 방식과 선정 수·
 * 임계값의 관계가 이 형태이며, 화면이 축 이름이 아니라 조건만 보고 가른다는 것을 지어낸 이름이 증명한다.
 * `registryFixture`에는 넣지 않는다 — 카드 수를 세는 기존 검사를 흔들지 않기 위해서다. 필요한 검사가
 * `withConditionalStrategy`로 한 벌을 만든다.
 */
export const conditionalStrategy: StrategyView = {
  strategyId: 'GATED_SPLIT',
  name: '문에 따라 나누기',
  description: '고른 문 쪽의 값만 쓴다.',
  targetWeightSource: 'COMPUTED',
  selectable: true,
  warmupTradingDays: 0,
  axes: [
    {
      name: 'gate',
      label: '문',
      description: '어느 쪽 값을 쓸지 정한다.',
      termId: 'synthetic-gate',
      inputType: 'CHOICE',
      choices: [
        { value: 'LEFT', label: '왼쪽', selectable: true },
        { value: 'RIGHT', label: '오른쪽', selectable: true },
      ],
      defaultValue: 'LEFT',
    },
    {
      name: 'leftGap',
      label: '왼쪽 간격',
      description: '문이 왼쪽일 때 쓰는 간격이다.',
      unit: '칸',
      inputType: 'NUMBER',
      range: { min: 1, max: 9, step: 1 },
      defaultValue: 3,
      appliesWhen: { axis: 'gate', value: 'LEFT' },
    },
    {
      name: 'rightPick',
      label: '오른쪽 고르기',
      description: '문이 오른쪽일 때 고를 수다.',
      inputType: 'CHOICE',
      choices: [
        { value: 1, label: '하나', selectable: true },
        { value: 2, label: '둘', selectable: true },
      ],
      defaultValue: 1,
      appliesWhen: { axis: 'gate', value: 'RIGHT' },
    },
  ],
  validationRules: [
    {
      code: 'LEFT_GAP_EXCEEDS_UNIVERSE',
      detail: '왼쪽 간격이 후보 섹터 집합의 크기보다 큽니다.',
      left: { axis: 'leftGap' },
      operator: 'GREATER_THAN',
      right: { context: 'UNIVERSE_SIZE' },
    },
  ],
}

/** 기본 한 벌에 조건부 전략을 더한 등록표. */
export function withConditionalStrategy(base: RegistryResponse = registryFixture): RegistryResponse {
  return { ...base, strategies: [...base.strategies, conditionalStrategy] }
}

/** 등록표 한 벌. 응답의 `strategies` 순서가 곧 카드 순서다. */
export const registryFixture: RegistryResponse = {
  registryVersion: 'TEST-REGISTRY-003-1',
  provisional: true,
  analysisConditions: {
    initialAmount: {
      min: 2_000_000,
      max: 500_000_000,
      step: 2_000_000,
      default: 20_000_000,
      unit: '원',
    },
    targetVolatility: {
      min: 4,
      max: 24,
      step: 2,
      default: 12,
      unit: '퍼센트(연)',
      usedBy: 'TARGET_RISK',
    },
    minEvaluationYears: {
      choices: [
        { value: 0, label: '완화(제한 없음)' },
        { value: 1, label: '기본(1년)' },
        { value: 3, label: '엄격(3년)' },
      ],
      default: 1,
      unit: '년',
    },
  },
  strategies: [selectableStrategy, unselectableStrategy, plainStrategy],
}
