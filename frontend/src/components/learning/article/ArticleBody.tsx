import { useMemo } from 'react'
import type {
  CurrentArticleBlock,
  CurrentLearningArticle,
  LearningReturnState,
  LegacyArticleBlock,
  LegacyLearningArticle,
} from '@/types/learning'
import { ComparisonTable } from './ComparisonTable'
import { firstOccurrences, termBlockKey, type OccurrenceMarks } from './firstOccurrences'
import { HypotheticalDiagram } from './HypotheticalDiagram'
import { InstitutionNote } from './InstitutionNote'
import { KeySummary } from './KeySummary'
import { Misconception } from './Misconception'
import { RichText, RichTextProvider } from './RichText'
import { TermDefinition } from './TermDefinition'
import styles from './ArticleBody.module.css'

interface ArticleBodyProps {
  /** 판과 블록이 짝을 이룬다. `Pick<LearningArticle, …>`으로 줄이면 둘의 짝이 풀린다 */
  article:
    | Pick<CurrentLearningArticle, 'edition' | 'blocks'>
    | Pick<LegacyLearningArticle, 'edition' | 'blocks'>
  /** 이 글이 받은 돌아갈 자리. 글 안 도움말의 글 연결에 그대로 싣는다(term-help-ui §7) */
  learningReturn?: LearningReturnState
}

/**
 * 글 본문을 그린다(013 FR-005·FR-008·FR-026). 판(`edition`)에 따라 9월 판 블록이나 새 기준 블록을 그린다.
 *
 * <p><b>텍스트 노드로만 그린다.</b> 서버가 보낸 문장에 태그 문자열이 있어도 태그로 해석하지 않는다
 * (013 research R3, FR-026).
 *
 * <p><b>모르는 블록 종류는 그리지 않는다.</b> 서버가 새 종류를 먼저 더해도 글의 나머지는 보인다.
 * 조용히 사라지면 원인을 찾기 어려우므로 개발 모드에서만 경고를 남긴다.
 *
 * <p><b>본문 끝 표지를 둔다.</b> 마지막 블록 바로 뒤의 `data-article-end` 요소가 완료 판정의 본문 끝이다
 * (FR-021). 예시와 글 끝 자리보다 앞이라 진단 버튼 아래까지 내려가지 않아도 된다. 새 기준 글은 목업 '글 끝'처럼
 * 가는 줄 사이에 '본문 끝'을 보이고, 9월 판 글은 지금 모습 그대로라 보이지 않는 표지만 둔다(FR-031).
 */
export function ArticleBody({ article, learningReturn }: ArticleBodyProps) {
  return (
    <div className={styles.body}>
      {article.edition === 'CURRENT' ? (
        <>
          <CurrentBlocks blocks={article.blocks} learningReturn={learningReturn} />
          <div className={styles.endMark} data-article-end="">
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
              <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" strokeWidth="1.3" />
              <circle cx="8" cy="8" r="4.4" fill="none" stroke="currentColor" strokeWidth="1" />
            </svg>
            <span>본문 끝</span>
          </div>
        </>
      ) : (
        <>
          {article.blocks.map((block, index) => (
            <LegacyBlock key={index} block={block} />
          ))}
          <div className={styles.end} data-article-end="" aria-hidden="true" />
        </>
      )}
    </div>
  )
}

function warnUnknown(block: unknown) {
  if (import.meta.env.DEV) {
    console.warn('알 수 없는 배우기 블록 종류라 그리지 않았습니다.', block)
  }
}

function LegacyBlock({ block }: { block: LegacyArticleBlock }) {
  switch (block.type) {
    case 'heading':
      return <h2 className={styles.heading}>{block.text}</h2>
    case 'paragraph':
      return <p className={styles.paragraph}>{block.text}</p>
    case 'term':
      return (
        <dl className={styles.term}>
          <dt>{block.label}</dt>
          <dd>{block.sentence}</dd>
        </dl>
      )
    case 'hypothetical':
      return (
        <aside className={styles.hypothetical} aria-label="가상의 계산 예">
          <span className={styles.hypotheticalLabel}>가상의 계산 예</span>
          <p>{block.text}</p>
        </aside>
      )
    default:
      warnUnknown(block)
      return null
  }
}

function CurrentBlocks({
  blocks,
  learningReturn,
}: {
  blocks: readonly CurrentArticleBlock[]
  learningReturn?: LearningReturnState
}) {
  // 처음 나오는 자리는 글 전체를 한 번 훑어 정한다. 블록마다 따로 정하면 순서를 잃는다(R18).
  const marks = useMemo(() => firstOccurrences(blocks), [blocks])
  const context = useMemo(() => ({ marks, learningReturn }), [marks, learningReturn])

  return (
    <RichTextProvider value={context}>
      {blocks.map((block, index) => (
        <CurrentBlock key={index} block={block} blockIndex={index} marks={marks} />
      ))}
    </RichTextProvider>
  )
}

function CurrentBlock({
  block,
  blockIndex,
  marks,
}: {
  block: CurrentArticleBlock
  blockIndex: number
  marks: OccurrenceMarks
}) {
  switch (block.type) {
    case 'keySummary':
      return <KeySummary lines={block.lines} blockIndex={blockIndex} />
    case 'heading':
      return <h2 className={styles.heading}>{block.text}</h2>
    case 'paragraph':
      return (
        <p className={styles.paragraph}>
          <RichText runs={block.content} blockIndex={blockIndex} field="content" />
        </p>
      )
    case 'term':
      return (
        <TermDefinition
          label={block.label}
          aliases={marks.aliasAt.has(termBlockKey(blockIndex)) ? block.aliases : []}
          meaning={block.meaning}
          reading={block.reading}
          caveat={block.caveat}
        />
      )
    case 'hypothetical':
      return (
        <aside
          className={`${styles.hypothetical} ${styles.hypotheticalCurrent}`}
          aria-label="가상의 계산 예"
        >
          <span className={styles.hypotheticalLabel}>가상의 계산 예</span>
          <p>
            <RichText runs={block.content} blockIndex={blockIndex} field="content" />
          </p>
        </aside>
      )
    case 'misconception':
      return <Misconception wrong={block.wrong} correction={block.correction} blockIndex={blockIndex} />
    case 'list': {
      const items = block.items.map((item, index) => (
        <li key={index}>
          <RichText runs={item} blockIndex={blockIndex} field={`items.${index}`} />
        </li>
      ))
      return block.ordered ? (
        <ol className={styles.list}>{items}</ol>
      ) : (
        <ul className={styles.list}>{items}</ul>
      )
    }
    case 'table':
      return (
        <ComparisonTable
          caption={block.caption}
          columns={block.columns}
          rows={block.rows}
          blockIndex={blockIndex}
        />
      )
    case 'diagram':
      return (
        <HypotheticalDiagram
          kind={block.kind}
          title={block.title}
          unit={block.unit}
          categories={block.categories}
          series={block.series}
          description={block.description}
        />
      )
    case 'institutionNote':
      return (
        <InstitutionNote
          content={block.content}
          asOf={block.asOf}
          source={block.source}
          blockIndex={blockIndex}
        />
      )
    default:
      warnUnknown(block)
      return null
  }
}
