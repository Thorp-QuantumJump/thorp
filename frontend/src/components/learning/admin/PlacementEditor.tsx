import type { ArticleFormat, StageAdminView } from '@/types/learningAdmin'
import { NumberField } from './NumberField'
import styles from './AdminEditor.module.css'

/**
 * 글의 초안 배치(단계·단계 안 순서)와 형식을 편집한다(013 research R15·R16, T169).
 *
 * <p><b>배치를 바꿔도 공개는 바뀌지 않는다.</b> 초안 배치는 다시 게시해야 공개 배치가 된다(FR-023). 그 사실을
 * 한 줄로 알린다.
 *
 * <p><b>형식을 바꾸면 본문·용어·확인 문제·출처를 비운다.</b> 두 형식 사이를 짐작해 옮기면 일부가 말없이 사라진다
 * (원칙 VI). 그래서 바꾸기 전에 확인을 받고, 그 형식의 원고를 가져오라고 안내한다(R25). V2에서 V1로 되돌리기는
 * 9월 판(LEGACY)을 공개한 적이 있는 글에서만 보인다 — 다른 글은 서버가 409 `LEARNING_FORMAT_OUTDATED`로
 * 거절한다(계약 §5).
 */

interface PlacementEditorProps {
  format: ArticleFormat
  stageKey: string
  stagePosition: number
  /** 단계 목록. 받지 못했으면 `null`이고 지금 단계만 선택지로 둔다 */
  stages: readonly StageAdminView[] | null
  /** 공개 중인 판이 9월 판(LEGACY)인가. 그때만 V1로 되돌리기를 보인다 */
  canRevertToV1: boolean
  onPlacementChange: (placement: { stageKey: string; stagePosition: number }) => void
  /** 확인을 받은 뒤에만 부른다 */
  onFormatChange: (format: ArticleFormat) => void
}

const FORMAT_NAMES: Record<ArticleFormat, string> = {
  V1: 'V1 (9월 형식, 이전 판 고치기)',
  V2: 'V2 (새 기준 형식)',
}

export function PlacementEditor({
  format,
  stageKey,
  stagePosition,
  stages,
  canRevertToV1,
  onPlacementChange,
  onFormatChange,
}: PlacementEditorProps) {
  const target: ArticleFormat | null = format === 'V1' ? 'V2' : canRevertToV1 ? 'V1' : null
  const known = stages?.some((stage) => stage.stageKey === stageKey) ?? false

  const changeFormat = (next: ArticleFormat) => {
    const confirmed = window.confirm(
      `형식을 ${next}로 바꾸면 본문 블록·용어·확인 문제·출처를 모두 비웁니다. 제목·질문·배치·예시는 남습니다. ` +
        `바꾸기 전에 '원고 내보내기'로 지금 내용을 받아 두고, 바꾼 뒤 ${next} 원고를 가져오세요. 바꿀까요?`,
    )
    if (confirmed) onFormatChange(next)
  }

  return (
    <section className={styles.editor} aria-labelledby="placement-editor-title">
      <h3 id="placement-editor-title">배치와 형식</h3>
      <p className={styles.guide}>
        배치는 저장한 뒤 다시 게시해야 공개에 반영됩니다. 공개 전 단계의 V2 글은 &lsquo;단계
        공개&rsquo;로만 게시됩니다.
      </p>
      <div className={styles.row}>
        <label className={styles.field}>
          <span>단계</span>
          <select
            value={stageKey}
            onChange={(event) => onPlacementChange({ stageKey: event.target.value, stagePosition })}
          >
            {known ? null : <option value={stageKey}>{stageKey}</option>}
            {(stages ?? []).map((stage) => (
              <option key={stage.stageKey} value={stage.stageKey}>
                {stage.number}단계 · {stage.name}
                {stage.releasedAt === null ? '' : ' (공개됨)'}
              </option>
            ))}
          </select>
        </label>
        <NumberField
          className={styles.field}
          label="단계 안 순서"
          integer
          value={stagePosition}
          onCommit={(next) => onPlacementChange({ stageKey, stagePosition: next })}
        />
      </div>
      {stages === null ? (
        <p className={styles.hint}>단계 목록을 불러오지 못해 지금 단계만 고를 수 있습니다.</p>
      ) : null}
      <p className={styles.notice}>
        형식 <span className={styles.badge}>{FORMAT_NAMES[format]}</span>
      </p>
      {target === null ? null : (
        <div className={styles.controls}>
          <button type="button" onClick={() => changeFormat(target)}>
            {target === 'V1' ? 'V1 형식으로 되돌리기' : 'V2 형식으로 바꾸기'}
          </button>
        </div>
      )}
    </section>
  )
}
