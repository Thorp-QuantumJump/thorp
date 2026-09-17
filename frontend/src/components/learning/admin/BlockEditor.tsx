import type { AdminDraftBlock, AdminDraftTerm } from '@/types/learningAdmin'
import styles from './BlockEditor.module.css'

/**
 * 본문 블록 목록을 편집한다(013 data-model §4, T062).
 *
 * <p><b>판정은 서버가 한다. 화면은 안내만 하고 저장을 막지 않는다.</b> 초안은 쓰다 만 값을 담을 수
 * 있어야 하고, 길이·개수 위반은 저장 요청이 400으로 알려 준다(계약 §5). 화면이 먼저 막으면
 * 운영자가 문단을 끝내기 전에는 아무것도 보관하지 못한다.
 *
 * <p><b>`term` 블록의 `termId`는 이 글의 용어 목록에서 고른다.</b> 직접 입력을 두면 이 글에 없는
 * 용어를 가리키기 쉽고, 그것은 저장은 되지만 게시에서 `TERM_BLOCKS_MATCH`로 걸린다. 다만 이미
 * 저장된 값이 목록에 없을 수는 있으므로 그 경우에도 선택지를 만들어 값을 잃지 않게 한다.
 */

interface BlockEditorProps {
  blocks: AdminDraftBlock[]
  /** 이 글이 소유한 용어. `term` 블록의 선택지가 된다 */
  terms: AdminDraftTerm[]
  onChange: (blocks: AdminDraftBlock[]) => void
}

/** data-model §4의 상한이다. 안내 문장에만 쓰고 입력을 자르지 않는다. */
const TEXT_LIMITS: Record<string, number> = {
  heading: 100,
  paragraph: 2000,
  hypothetical: 1000,
}

const BLOCK_LABELS: Record<AdminDraftBlock['type'], string> = {
  heading: '소제목',
  paragraph: '문단',
  term: '용어',
  hypothetical: '가상의 계산 예',
}

const MAX_BLOCKS = 60

function emptyBlock(type: AdminDraftBlock['type'], terms: AdminDraftTerm[]): AdminDraftBlock {
  if (type === 'term') return { type: 'term', termId: terms[0]?.termId ?? '' }
  return { type, text: '' }
}

export function BlockEditor({ blocks, terms, onChange }: BlockEditorProps) {
  const replaceAt = (index: number, next: AdminDraftBlock) => {
    onChange(blocks.map((block, position) => (position === index ? next : block)))
  }

  const move = (index: number, step: -1 | 1) => {
    const target = index + step
    if (target < 0 || target >= blocks.length) return
    const next = [...blocks]
    const [moved] = next.splice(index, 1)
    next.splice(target, 0, moved)
    onChange(next)
  }

  return (
    <section className={styles.editor} aria-labelledby="block-editor-title">
      <h3 id="block-editor-title">본문</h3>
      <p className={styles.guide}>
        블록은 최대 {MAX_BLOCKS}개입니다. 실제 결과로 읽히는 숫자는 &lsquo;가상의 계산 예&rsquo;
        블록에만 쓸 수 있습니다.
      </p>

      <ol className={styles.list}>
        {blocks.map((block, index) => (
          // 블록은 순서가 곧 정체성이라 내용으로 키를 만들면 같은 문단 둘이 한 칸을 다툰다.
          <li key={index} className={styles.item}>
            <div className={styles.head}>
              <span className={styles.kind}>
                {index + 1}. {BLOCK_LABELS[block.type]}
              </span>
              <div className={styles.controls}>
                <button
                  type="button"
                  onClick={() => move(index, -1)}
                  disabled={index === 0}
                  aria-label={`${index + 1}번째 블록 위로`}
                >
                  위로
                </button>
                <button
                  type="button"
                  onClick={() => move(index, 1)}
                  disabled={index === blocks.length - 1}
                  aria-label={`${index + 1}번째 블록 아래로`}
                >
                  아래로
                </button>
                <button
                  type="button"
                  onClick={() => onChange(blocks.filter((_, position) => position !== index))}
                  aria-label={`${index + 1}번째 블록 삭제`}
                >
                  삭제
                </button>
              </div>
            </div>

            {block.type === 'term' ? (
              <label className={styles.field}>
                <span>용어</span>
                <select
                  value={block.termId}
                  onChange={(event) => replaceAt(index, { type: 'term', termId: event.target.value })}
                >
                  {/* 저장된 값이 용어 목록에 없어도 그대로 보관한다. 지우는 것은 운영자가 정한다. */}
                  {terms.every((term) => term.termId !== block.termId) ? (
                    <option value={block.termId}>{block.termId || '(고르지 않음)'}</option>
                  ) : null}
                  {terms.map((term) => (
                    <option key={term.termId} value={term.termId}>
                      {term.label ? `${term.label} (${term.termId})` : term.termId}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <label className={styles.field}>
                <span>
                  내용 (최대 {TEXT_LIMITS[block.type]}자, 지금 {block.text.length}자)
                </span>
                <textarea
                  value={block.text}
                  rows={block.type === 'heading' ? 1 : 4}
                  onChange={(event) =>
                    replaceAt(index, { type: block.type, text: event.target.value })
                  }
                />
              </label>
            )}
          </li>
        ))}
      </ol>

      {blocks.length === 0 ? <p className={styles.empty}>블록이 없습니다.</p> : null}

      <div className={styles.add}>
        {(Object.keys(BLOCK_LABELS) as AdminDraftBlock['type'][]).map((type) => (
          <button
            key={type}
            type="button"
            onClick={() => onChange([...blocks, emptyBlock(type, terms)])}
          >
            {BLOCK_LABELS[type]} 추가
          </button>
        ))}
      </div>
    </section>
  )
}
