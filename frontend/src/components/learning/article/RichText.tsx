import { createContext, useContext, useRef, type ReactNode } from 'react'
import { TermHelp } from '@/components/learning/TermHelp'
import type { LearningReturnState, NoteSource, Run } from '@/types/learning'
import {
  aliasSuffix,
  isTermMention,
  runKey,
  type OccurrenceMarks,
  type RunField,
} from './firstOccurrences'
import styles from './RichText.module.css'

/**
 * 글 하나에서 조각을 그릴 때 함께 쓰는 값. 블록마다 넘기지 않으려고 문맥으로 둔다.
 *
 * - `marks`: `firstOccurrences`가 정한 처음 나오는 자리
 * - `learningReturn`: 이 글이 받은 돌아갈 자리. 글 안 도움말의 글 연결에 그대로 싣는다(term-help-ui §7)
 */
type RichTextContextValue = {
  marks: OccurrenceMarks
  learningReturn?: LearningReturnState
}

const EMPTY_MARKS: OccurrenceMarks = { aliasAt: new Set(), helpAt: new Set() }

const RichTextContext = createContext<RichTextContextValue>({ marks: EMPTY_MARKS })

export function RichTextProvider({
  value,
  children,
}: {
  value: RichTextContextValue
  children: ReactNode
}) {
  return <RichTextContext.Provider value={value}>{children}</RichTextContext.Provider>
}

interface SourceLinkProps {
  source: NoteSource
}

/** 출처 이름 상자가 화면 가장자리에서 띄울 거리(px). */
const TOOLTIP_EDGE = 8

/**
 * 출처 표시 '(*출처)'(013 FR-007, FR-026).
 *
 * <p><b>인용한 글자는 링크가 아니다.</b> 그 글자 바로 옆의 이 표시만 링크이고 새 탭에서 연다. 마우스를
 * 올리거나 초점을 두면 출처 이름이 보인다. 스크린 리더는 출처 이름과 새 탭에서 여는 외부 링크임을 읽는다.
 *
 * <p><b>출처 이름은 화면 안에 들인다.</b> 이름 상자는 표시 위 가운데에 뜨는데, 줄 끝이나 줄 처음의 표시라면
 * 화면 밖으로 나가 잘린다. 열릴 때 상자의 자리를 재서 가장자리 안쪽으로 민다.
 */
export function SourceLink({ source }: SourceLinkProps) {
  const name = source.label || source.url
  const nameRef = useRef<HTMLSpanElement>(null)

  const keepNameInside = () => {
    const tooltip = nameRef.current
    if (!tooltip) return
    tooltip.style.setProperty('--tooltip-shift', '0px')
    // 마우스·초점 상태가 반영되어 상자가 보인 뒤에 잰다.
    requestAnimationFrame(() => {
      const rect = tooltip.getBoundingClientRect()
      if (rect.width === 0) return
      const viewport = document.documentElement.clientWidth
      let shift = 0
      if (rect.left < TOOLTIP_EDGE) shift = TOOLTIP_EDGE - rect.left
      else if (rect.right > viewport - TOOLTIP_EDGE) shift = viewport - TOOLTIP_EDGE - rect.right
      tooltip.style.setProperty('--tooltip-shift', `${Math.round(shift)}px`)
    })
  }

  return (
    // 누름 영역과 이름 상자는 이 자리를 기준으로 놓는다. 위첨자로 올린 표시가 아니라 본문 글자 높이에 맞춘다.
    <span className={styles.sourceSlot}>
      <a
        className={styles.source}
        href={source.url}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={`출처: ${name} (외부 링크, 새 탭에서 열림)`}
        onMouseEnter={keepNameInside}
        onFocus={keepNameInside}
      >
        (*출처)
        <span ref={nameRef} className={styles.sourceName} aria-hidden="true">
          {name}
        </span>
      </a>
    </span>
  )
}

interface RichTextProps {
  runs: readonly Run[]
  /** 이 조각 배열이 놓인 블록 순번 */
  blockIndex: number
  /** 블록 안 필드 이름(`firstOccurrences`의 `runFieldsOf`와 같은 이름) */
  field: RunField
}

/**
 * 글자 조각 배열을 그린다(013 data-model §5-2, research R17).
 *
 * <p><b>텍스트 노드로만 그린다.</b> 서버가 보낸 글자에 태그 문자열이 있어도 태그로 해석하지 않는다
 * (FR-026). 조각 구조가 링크·용어의 자리를 알려 주므로 문자열을 해석할 일이 없다.
 *
 * <p>용어 언급은 처음 나오는 자리에 다른 이름을 붙이고, 다른 글이 소유한 용어면 그 자리에 도움말 표시를
 * 둔다. 용어 글자 자체는 누를 수 없다(FR-009).
 */
export function RichText({ runs, blockIndex, field }: RichTextProps) {
  const { marks, learningReturn } = useContext(RichTextContext)

  return (
    <>
      {runs.map((run, runIndex) => {
        if ('source' in run) return <SourceLink key={runIndex} source={run.source} />
        if (!isTermMention(run)) return run.text

        const key = runKey(blockIndex, field, runIndex)
        const withAliases = marks.aliasAt.has(key)
        const withHelp = marks.helpAt.has(key)
        return (
          <span key={runIndex}>
            {run.text}
            {withAliases ? aliasSuffix(run.aliases) : null}
            {withHelp ? (
              <span className={styles.help}>
                <TermHelp termId={run.term} label={run.text} returnTo={learningReturn} />
              </span>
            ) : null}
          </span>
        )
      })}
    </>
  )
}
