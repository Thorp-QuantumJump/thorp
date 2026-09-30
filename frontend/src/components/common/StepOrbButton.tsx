import { ArrowRight } from 'lucide-react'
import type { ComponentPropsWithoutRef, ReactNode } from 'react'
import styles from './StepOrbButton.module.css'

/**
 * 원이 드러난 정도다. 0은 카드 테두리에 초승달만 남긴 숨음, 1은 절반쯤 밀려 나온 상태, 2는 다 드러나 누를
 * 수 있는 상태다. 1은 "하나만 더 하면 열린다"를 움직임으로 말하는 자리다.
 */
export type StepOrbStage = 0 | 1 | 2

type StepOrbButtonProps = Omit<
  ComponentPropsWithoutRef<'button'>,
  'children' | 'className' | 'type'
> & {
  stage: StepOrbStage
  /** 원 위쪽 작은 글자. 어느 단계로 넘어가는지 적는다('STEP 02'). */
  step: string
  /** 원 가운데 굵은 글자('전략 선택', '분석하기'). */
  label: string
  /** 원 옆이나 아래에 딸리는 글(고른 개수 안내 등). 원과 같은 칸에 놓인다. */
  children?: ReactNode
  /** 원이 놓이는 칸(dock)에 더할 클래스. 격자 자리와 위아래 여백은 쓰는 화면이 정한다. */
  className?: string
  'data-action'?: string
}

/**
 * 다음 단계로 넘어가는 원형 단추다. 진단 흐름의 화면들이 같은 모양·움직임을 쓰도록 한 곳에 둔다.
 *
 * <b>카드 오른쪽 테두리에 걸쳐 선다.</b> 왼쪽은 채워져 있고 오른쪽으로 갈수록 배경에 스며들어, 카드 밖
 * 다음 단계로 넘어가는 자리로 읽힌다. 원이 놓이는 칸(dock)의 폭이 단계에 따라 넓어지므로, 이 칸과 한
 * 격자에 놓인 카드는 원이 드러나는 만큼 같은 속도로 좁아진다.
 *
 * <b>세 단계는 한 색상 계열에서 밝기만 오른다.</b> 숨은 원이 다른 색이면 앞 단계가 아니라 다른 물건으로
 * 보인다. 단계가 바뀌면 드러남·모양·색·칸 폭이 같은 시간과 곡선으로 움직이고, 숨을 때는 오른쪽으로 밀려
 * 나가며 흐려진다.
 *
 * <b>막는 방식은 쓰는 쪽이 정한다.</b> 조건이 채워지기 전 눌릴 일이 없는 화면은 `disabled`를, 왜 막혔는지
 * 보조 기술이 읽어야 하는 화면은 `aria-disabled`와 `aria-describedby`를 넘긴다. 둘 다 같은 숨은 모양을
 * 얻는다. 글자는 `aria-hidden`이라 접근 가능 이름은 `aria-label`로 넘겨야 한다.
 */
export function StepOrbButton({
  stage,
  step,
  label,
  children,
  className,
  ...buttonProps
}: StepOrbButtonProps) {
  return (
    <div className={className ? `${styles.dock} ${className}` : styles.dock} data-stage={stage}>
      <button type="button" className={styles.orb} data-stage={stage} {...buttonProps}>
        <span className={styles.step} aria-hidden="true">
          {step}
        </span>
        <span className={styles.label} aria-hidden="true">
          {label}
        </span>
        <ArrowRight className={styles.arrow} size={16} strokeWidth={2.2} aria-hidden="true" />
      </button>
      {children}
    </div>
  )
}
