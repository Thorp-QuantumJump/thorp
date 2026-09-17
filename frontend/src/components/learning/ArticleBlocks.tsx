import type { ArticleBlock } from '@/types/learning'
import styles from './ArticleBlocks.module.css'

interface ArticleBlocksProps {
  blocks: readonly ArticleBlock[]
}

/**
 * 글 본문 블록을 그린다(013 FR-005·FR-008).
 *
 * <p><b>텍스트 노드로만 그린다.</b> 서버가 보낸 문장에 태그 문자열이 있어도 태그로 해석하지 않는다
 * (013 research R3). 본문 형식을 네 블록 종류로 제한한 까닭이 마크업 해석을 두지 않기 위해서다.
 *
 * <p><b>모르는 블록 종류는 그리지 않는다.</b> 서버가 새 종류를 먼저 더해도 글의 나머지는 보인다.
 * 조용히 사라지면 원인을 찾기 어려우므로 개발 모드에서만 경고를 남긴다.
 */
export function ArticleBlocks({ blocks }: ArticleBlocksProps) {
  return (
    <div className={styles.blocks}>
      {blocks.map((block, index) => (
        <Block key={index} block={block} />
      ))}
    </div>
  )
}

function Block({ block }: { block: ArticleBlock }) {
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
      if (import.meta.env.DEV) {
        console.warn('알 수 없는 배우기 블록 종류라 그리지 않았습니다.', block)
      }
      return null
  }
}
