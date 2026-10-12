import type { ReactNode } from 'react'
import type { AdminDraftBlockV1, AdminDraftBlockV2 } from '@/types/learningAdmin'
import { BLOCK_LABELS } from './draftLabels'
import type { EditableBlockV2, EditableDraft } from './editableDraft'
import { TermSelect, V2BlockFields, type TermOption } from './V2BlockFields'
import styles from './BlockEditor.module.css'

/**
 * 본문 블록 목록을 편집한다(013 data-model §5, T062·T169).
 *
 * <p><b>(2판) 형식에 따라 블록 종류가 다르다.</b> V1 초안은 9월 판 네 종류(소제목·문단·용어·가상의 계산 예)만
 * 두고, V2 초안은 열 종류를 둔다(research R16). V1 초안에 V2 블록을 넣으면 저장이 400이므로 추가 단추부터
 * 만들지 않는다.
 *
 * <p><b>판정은 서버가 한다. 화면은 안내만 하고 저장을 막지 않는다.</b> 초안은 쓰다 만 값을 담을 수
 * 있어야 하고, 길이·개수 위반은 저장 요청이 400으로 알려 준다(계약 §5). 화면이 먼저 막으면
 * 운영자가 문단을 끝내기 전에는 아무것도 보관하지 못한다.
 *
 * <p><b>`term` 블록의 `termId`는 이 글의 용어 목록에서 고른다.</b> 직접 입력을 두면 이 글에 없는
 * 용어를 가리키기 쉽고, 그것은 저장은 되지만 게시에서 `TERM_BLOCKS_MATCH`로 걸린다. 다만 이미
 * 저장된 값이 목록에 없을 수는 있으므로 그 경우에도 선택지를 만들어 값을 잃지 않게 한다.
 *
 * <p>초안을 통째로 받고 돌려준다. 형식에 따라 블록 타입이 달라 부르는 쪽이 형식마다 갈라 부르지 않게 하려는
 * 것이다.
 */

interface BlockEditorProps {
  draft: EditableDraft
  onChange: (draft: EditableDraft) => void
}

/** data-model §5-1의 상한이다. 안내 문장에만 쓰고 입력을 자르지 않는다. */
const V1_TEXT_LIMITS: Record<string, number> = { heading: 100, paragraph: 2000, hypothetical: 1000 }

const V1_TYPES: AdminDraftBlockV1['type'][] = ['heading', 'paragraph', 'term', 'hypothetical']

const V2_TYPES: AdminDraftBlockV2['type'][] = [
  'keySummary',
  'heading',
  'paragraph',
  'term',
  'hypothetical',
  'misconception',
  'list',
  'table',
  'diagram',
  'institutionNote',
]

const MAX_BLOCKS = { V1: 60, V2: 80 } as const

function emptyV1(type: AdminDraftBlockV1['type'], terms: readonly TermOption[]): AdminDraftBlockV1 {
  if (type === 'term') return { type: 'term', termId: terms[0]?.termId ?? '' }
  return { type, text: '' }
}

/** 새 V2 블록. 표·도식은 저장 하한(열 2개, 칸 2개, 계열 1개)을 채운 빈 모양으로 시작한다. */
function emptyV2(type: AdminDraftBlockV2['type'], terms: readonly TermOption[]): EditableBlockV2 {
  switch (type) {
    case 'keySummary':
      return { type, lines: ['', '', ''] }
    case 'heading':
      return { type, text: '' }
    case 'paragraph':
    case 'hypothetical':
      return { type, content: '' }
    case 'term':
      return { type, termId: terms[0]?.termId ?? '' }
    case 'misconception':
      return { type, wrong: '', correction: '' }
    case 'list':
      return { type, ordered: false, items: [''] }
    case 'table':
      return { type, caption: '', columns: ['', ''], rows: [['', '']] }
    case 'diagram':
      return {
        type,
        kind: 'bar',
        title: '',
        unit: '',
        categories: ['', ''],
        series: [{ name: '', values: [0, 0] }],
        description: '',
      }
    case 'institutionNote':
      return { type, content: '', asOf: null, source: null }
  }
}

interface BlockListProps<B extends { type: keyof typeof BLOCK_LABELS }> {
  blocks: B[]
  types: readonly B['type'][]
  maxBlocks: number
  guide: ReactNode
  create: (type: B['type']) => B
  renderFields: (block: B, index: number, replace: (next: B) => void) => ReactNode
  onChange: (blocks: B[]) => void
}

function BlockList<B extends { type: keyof typeof BLOCK_LABELS }>({
  blocks,
  types,
  maxBlocks,
  guide,
  create,
  renderFields,
  onChange,
}: BlockListProps<B>) {
  const replaceAt = (index: number, next: B) => {
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
      <p className={styles.guide}>{guide}</p>

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
            {renderFields(block, index, (next) => replaceAt(index, next))}
          </li>
        ))}
      </ol>

      {blocks.length === 0 ? <p className={styles.empty}>블록이 없습니다.</p> : null}

      <div className={styles.add}>
        {types.map((type) => (
          <button
            key={type}
            type="button"
            onClick={() => onChange([...blocks, create(type)])}
            disabled={blocks.length >= maxBlocks}
          >
            {BLOCK_LABELS[type]} 추가
          </button>
        ))}
      </div>
    </section>
  )
}

export function BlockEditor({ draft, onChange }: BlockEditorProps) {
  // 이 글이 소유한 용어가 `term` 블록의 선택지가 된다.
  const terms = draft.terms.map((term) => ({ termId: term.termId, label: term.label }))

  if (draft.format === 'V2') {
    return (
      <BlockList
        blocks={draft.blocks}
        types={V2_TYPES}
        maxBlocks={MAX_BLOCKS.V2}
        guide={
          <>
            블록은 최대 {MAX_BLOCKS.V2}개입니다. 글자 칸에는 {'{{용어:식별자|글자}}'}와{' '}
            {'{{출처:이름|주소}}'}를 쓸 수 있고, 글자 {'{{'}는 {'\\{{'}로 씁니다.
          </>
        }
        create={(type) => emptyV2(type, terms)}
        renderFields={(block, index, replace) => (
          <V2BlockFields block={block} number={index + 1} terms={terms} onChange={replace} />
        )}
        onChange={(blocks) => onChange({ ...draft, blocks })}
      />
    )
  }

  return (
    <BlockList
      blocks={draft.blocks}
      types={V1_TYPES}
      maxBlocks={MAX_BLOCKS.V1}
      guide={
        <>
          블록은 최대 {MAX_BLOCKS.V1}개입니다. 실제 결과로 읽히는 숫자는 &lsquo;가상의 계산
          예&rsquo; 블록에만 쓸 수 있습니다.
        </>
      }
      create={(type) => emptyV1(type, terms)}
      renderFields={(block, _index, replace) =>
        block.type === 'term' ? (
          <TermSelect
            label="용어"
            value={block.termId}
            terms={terms}
            onChange={(termId) => replace({ type: 'term', termId })}
          />
        ) : (
          <label className={styles.field}>
            <span>
              내용 (최대 {V1_TEXT_LIMITS[block.type]}자, 지금 {block.text.length}자)
            </span>
            <textarea
              value={block.text}
              rows={block.type === 'heading' ? 1 : 4}
              onChange={(event) => replace({ type: block.type, text: event.target.value })}
            />
          </label>
        )
      }
      onChange={(blocks) => onChange({ ...draft, blocks })}
    />
  )
}
