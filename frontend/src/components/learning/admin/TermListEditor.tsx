import { useState } from 'react'
import type { AdminDraftTermV1, AdminDraftTermV2 } from '@/types/learningAdmin'
import styles from './TermListEditor.module.css'

/**
 * 이 글이 소유한 용어 설명을 편집한다(013 FR-008, T064·T170).
 *
 * <p><b>저장된 행의 `termId`는 읽기 전용이다.</b> 식별자는 만들 때 정하고 이후 바꾸지 않는다
 * (research R4). 002·003·005 화면 코드가 이 값을 적어 두므로, 바꾸면 그쪽 도움말이 조용히 끊긴다.
 *
 * <p><b>저장 요청은 목록을 통째로 보낸다.</b> 여기서 지운 행은 초안에서 빠지고, 다음 게시 때
 * 공개에서도 빠진다(계약 §5). 그 사실을 한 줄로 알린다.
 *
 * <p><b>(2판) 형식에 따라 칸이 다르다.</b> V1 용어는 설명 문장 하나이고, V2 용어는 다른 이름·뜻·읽는 법·한계다
 * (data-model §7-1). 넘겨받는 중인 용어(`transferFrom`)는 그 사실과 취소 단추를 보인다. 넘겨받기를 거는 일은
 * 저장이 `LEARNING_TERM_ID_TAKEN`을 받았을 때 편집 화면이 제안한다(R24).
 */

type TermListEditorProps =
  | {
      format: 'V1'
      terms: AdminDraftTermV1[]
      /** 서버에 이미 저장된 식별자. 이 목록에 있는 행은 `termId`를 바꿀 수 없다 */
      savedTermIds: readonly string[]
      onChange: (terms: AdminDraftTermV1[]) => void
    }
  | {
      format: 'V2'
      terms: AdminDraftTermV2[]
      savedTermIds: readonly string[]
      onChange: (terms: AdminDraftTermV2[]) => void
    }

const LABEL_MAX_LENGTH = 50
const SENTENCE_MAX_LENGTH = 300
const V2_SENTENCE_MAX_LENGTH = 200
const ALIAS_MAX_LENGTH = 40
const MAX_ALIASES = 4

function replaceAt<T>(items: readonly T[], index: number, patch: Partial<T>): T[] {
  return items.map((item, position) => (position === index ? { ...item, ...patch } : item))
}

export function TermListEditor(props: TermListEditorProps) {
  const [newTermId, setNewTermId] = useState('')

  const addTerm = () => {
    const termId = newTermId.trim()
    if (termId === '') return
    if (props.format === 'V1') {
      props.onChange([...props.terms, { termId, label: '', sentence: '' }])
    } else {
      props.onChange([
        ...props.terms,
        {
          termId,
          label: '',
          aliases: [],
          meaning: '',
          reading: '',
          caveat: '',
          transferFrom: null,
        },
      ])
    }
    setNewTermId('')
  }

  // 두 형식이 함께 가진 칸(식별자·이름)을 고치고 행을 지운다. 형식마다 갈라 부르는 것은 `onChange`의 타입을
  // 형식에 맞춰 좁히기 위해서다.
  const patchCommon = (index: number, patch: { termId?: string; label?: string }) => {
    if (props.format === 'V1') props.onChange(replaceAt(props.terms, index, patch))
    else props.onChange(replaceAt(props.terms, index, patch))
  }

  const removeAt = (index: number) => {
    if (props.format === 'V1')
      props.onChange(props.terms.filter((_, position) => position !== index))
    else props.onChange(props.terms.filter((_, position) => position !== index))
  }

  return (
    <section className={styles.editor} aria-labelledby="term-editor-title">
      <h3 id="term-editor-title">용어 설명</h3>
      <p className={styles.guide}>
        여기서 지운 용어는 다음 게시 때 공개에서도 빠집니다. 식별자는 만든 뒤 바꿀 수 없습니다.
      </p>

      <ul className={styles.list}>
        {props.terms.map((term, index) => {
          const isSaved = props.savedTermIds.includes(term.termId)
          return (
            // 순번을 키로 쓴다. `termId`는 새 행에서 고칠 수 있는 값이라 키로 쓰면 글자마다 행이
            // 다시 만들어져 초점을 잃고, 같은 식별자 두 행이 키를 다툰다. 입력은 모두 제어 입력이라
            // 행을 지워도 값이 엇갈리지 않는다.
            <li key={index} className={styles.row}>
              <label className={styles.field}>
                <span>식별자</span>
                <input
                  type="text"
                  value={term.termId}
                  readOnly={isSaved}
                  aria-readonly={isSaved}
                  onChange={(event) => patchCommon(index, { termId: event.target.value })}
                />
              </label>

              <label className={styles.field}>
                <span>
                  이름 (최대 {LABEL_MAX_LENGTH}자, 지금 {term.label.length}자)
                </span>
                <input
                  type="text"
                  value={term.label}
                  onChange={(event) => patchCommon(index, { label: event.target.value })}
                />
              </label>

              {props.format === 'V1' ? (
                <label className={styles.field}>
                  <span>
                    설명 문장 (최대 {SENTENCE_MAX_LENGTH}자, 지금{' '}
                    {props.terms[index].sentence.length}
                    자)
                  </span>
                  <textarea
                    rows={3}
                    value={props.terms[index].sentence}
                    onChange={(event) =>
                      props.onChange(
                        replaceAt(props.terms, index, {
                          sentence: event.target.value,
                        }),
                      )
                    }
                  />
                </label>
              ) : (
                <V2TermFields
                  term={props.terms[index]}
                  onChange={(patch) => props.onChange(replaceAt(props.terms, index, patch))}
                />
              )}

              <button
                type="button"
                onClick={() => removeAt(index)}
                aria-label={`용어 ${term.termId} 삭제`}
              >
                삭제
              </button>
            </li>
          )
        })}
      </ul>

      {props.terms.length === 0 ? <p className={styles.empty}>용어가 없습니다.</p> : null}

      <div className={styles.add}>
        <label className={styles.field}>
          <span>새 용어 식별자</span>
          <input
            type="text"
            value={newTermId}
            onChange={(event) => setNewTermId(event.target.value)}
          />
        </label>
        <button type="button" onClick={addTerm} disabled={newTermId.trim() === ''}>
          용어 추가
        </button>
      </div>
    </section>
  )
}

function V2TermFields({
  term,
  onChange,
}: {
  term: AdminDraftTermV2
  onChange: (patch: Partial<AdminDraftTermV2>) => void
}) {
  const sentence = (key: 'meaning' | 'reading' | 'caveat', label: string) => (
    <label className={styles.field}>
      <span>
        {label} (최대 {V2_SENTENCE_MAX_LENGTH}자, 지금 {term[key].length}자)
      </span>
      <textarea
        rows={2}
        value={term[key]}
        onChange={(event) => onChange({ [key]: event.target.value })}
      />
    </label>
  )

  return (
    <>
      <fieldset className={styles.list}>
        <legend className={styles.guide}>
          다른 이름 (최대 {MAX_ALIASES}개, 처음 나오는 자리에 &lsquo;이름 (다른 이름)&rsquo;으로
          붙습니다)
        </legend>
        {term.aliases.map((alias, index) => (
          <div key={index} className={styles.inline}>
            <label className={styles.field}>
              <span>
                용어 {term.termId} {index + 1}번째 다른 이름 (최대 {ALIAS_MAX_LENGTH}자)
              </span>
              <input
                type="text"
                value={alias}
                onChange={(event) =>
                  onChange({
                    aliases: term.aliases.map((item, position) =>
                      position === index ? event.target.value : item,
                    ),
                  })
                }
              />
            </label>
            <button
              type="button"
              onClick={() =>
                onChange({
                  aliases: term.aliases.filter((_, position) => position !== index),
                })
              }
              aria-label={`용어 ${term.termId} ${index + 1}번째 다른 이름 삭제`}
            >
              삭제
            </button>
          </div>
        ))}
        <div className={styles.add}>
          <button
            type="button"
            onClick={() => onChange({ aliases: [...term.aliases, ''] })}
            disabled={term.aliases.length >= MAX_ALIASES}
          >
            다른 이름 추가
          </button>
        </div>
      </fieldset>

      {sentence('meaning', '뜻')}
      {sentence('reading', '읽는 법')}
      {sentence('caveat', '한계')}

      {term.transferFrom === null ? null : (
        <div className={styles.inline}>
          <p className={styles.transfer} role="note">
            넘겨받는 중: <strong>{term.transferFrom}</strong> 글이 소유한 용어입니다. 이 글이 게시될
            때 이 글로 넘어옵니다.
          </p>
          <button type="button" onClick={() => onChange({ transferFrom: null })}>
            넘겨받기 취소
          </button>
        </div>
      )}
    </>
  )
}
