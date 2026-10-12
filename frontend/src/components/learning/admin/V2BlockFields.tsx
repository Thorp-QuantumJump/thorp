import type { ReactNode } from 'react'
import type { DraftSourceRef } from '@/types/learningAdmin'
import { FIELD_RUNS, type EditableBlockV2 } from './editableDraft'
import { NumberField } from './NumberField'
import { RichTextField } from './RichTextField'
import styles from './AdminEditor.module.css'

/**
 * V2 블록 하나의 입력 칸들(013 data-model §5-3, T169).
 *
 * <p>글자 조각 필드는 `RichTextField`(편집 문법 문자열)다. 표·도식·제도 안내는 전용 입력을 둔다. 개수·길이
 * 상한은 안내만 하고 판정은 서버가 한다(계약 §5). 다만 추가 단추는 상한에서 멈춘다 — 상한을 넘는 칸은
 * 저장에서 반드시 거절되므로 만들 이유가 없다.
 */

type EditableBlock = EditableBlockV2

export type TermOption = { termId: string; label: string }

interface V2BlockFieldsProps {
  block: EditableBlock
  /** 블록 순번(1부터). 칸 이름표가 겹치지 않게 앞에 붙인다 */
  number: number
  terms: readonly TermOption[]
  onChange: (block: EditableBlock) => void
}

const LIMITS = {
  keySummaryLines: 3,
  keySummaryLine: 120,
  heading: 100,
  paragraph: 2000,
  hypothetical: 1000,
  wrong: 300,
  correction: 600,
  listItems: 8,
  listItem: 300,
  caption: 100,
  minColumns: 2,
  maxColumns: 5,
  column: 30,
  rows: 8,
  cell: 100,
  diagramTitle: 60,
  unit: 10,
  minCategories: 2,
  maxCategories: 12,
  category: 12,
  maxSeries: 3,
  seriesName: 20,
  description: 300,
  note: 600,
} as const

function replaceAt<T>(items: readonly T[], index: number, next: T): T[] {
  return items.map((item, position) => (position === index ? next : item))
}

function removeAt<T>(items: readonly T[], index: number): T[] {
  return items.filter((_, position) => position !== index)
}

/** 이 글의 용어 목록에서 고른다. 저장된 값이 목록에 없어도 선택지를 만들어 값을 잃지 않는다. */
export function TermSelect({
  label,
  value,
  terms,
  onChange,
}: {
  label: string
  value: string
  terms: readonly TermOption[]
  onChange: (termId: string) => void
}) {
  return (
    <label className={styles.field}>
      <span>{label}</span>
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        {terms.every((term) => term.termId !== value) ? (
          <option value={value}>{value || '(고르지 않음)'}</option>
        ) : null}
        {terms.map((term) => (
          <option key={term.termId} value={term.termId}>
            {term.label ? `${term.label} (${term.termId})` : term.termId}
          </option>
        ))}
      </select>
    </label>
  )
}

function TextInput({
  label,
  value,
  max,
  onChange,
}: {
  label: string
  value: string
  max: number
  onChange: (value: string) => void
}) {
  return (
    <label className={styles.field}>
      <span>
        {label} (최대 {max}자, 지금 {value.length}자)
      </span>
      <input type="text" value={value} onChange={(event) => onChange(event.target.value)} />
    </label>
  )
}

function ListControls({
  children,
  onAdd,
  addLabel,
  canAdd,
}: {
  children: ReactNode
  onAdd: () => void
  addLabel: string
  canAdd: boolean
}) {
  return (
    <div className={styles.list}>
      {children}
      <div className={styles.add}>
        <button type="button" onClick={onAdd} disabled={!canAdd}>
          {addLabel}
        </button>
      </div>
    </div>
  )
}

export function V2BlockFields({ block, number, terms, onChange }: V2BlockFieldsProps) {
  const name = (label: string) => `${number}번째 블록 ${label}`

  switch (block.type) {
    case 'keySummary':
      return (
        <ListControls
          addLabel="요약 줄 추가"
          canAdd={block.lines.length < LIMITS.keySummaryLines}
          onAdd={() => onChange({ ...block, lines: [...block.lines, ''] })}
        >
          {block.lines.map((line, index) => (
            <div key={index} className={styles.row}>
              <RichTextField
                label={name(`${index + 1}번째 줄`)}
                value={line}
                maxLength={LIMITS.keySummaryLine}
                allow={FIELD_RUNS.keySummary}
                rows={2}
                onChange={(value) =>
                  onChange({ ...block, lines: replaceAt(block.lines, index, value) })
                }
              />
              <button
                type="button"
                onClick={() => onChange({ ...block, lines: removeAt(block.lines, index) })}
                aria-label={name(`${index + 1}번째 줄 삭제`)}
              >
                삭제
              </button>
            </div>
          ))}
        </ListControls>
      )

    case 'heading':
      return (
        <TextInput
          label={name('소제목')}
          value={block.text}
          max={LIMITS.heading}
          onChange={(text) => onChange({ ...block, text })}
        />
      )

    case 'paragraph':
      return (
        <RichTextField
          label={name('내용')}
          value={block.content}
          maxLength={LIMITS.paragraph}
          allow={FIELD_RUNS.paragraph}
          rows={5}
          onChange={(content) => onChange({ ...block, content })}
        />
      )

    case 'term':
      return (
        <TermSelect
          label={name('용어')}
          value={block.termId}
          terms={terms}
          onChange={(termId) => onChange({ ...block, termId })}
        />
      )

    case 'hypothetical':
      return (
        <RichTextField
          label={name('내용')}
          value={block.content}
          maxLength={LIMITS.hypothetical}
          allow={FIELD_RUNS.hypothetical}
          rows={4}
          onChange={(content) => onChange({ ...block, content })}
        />
      )

    case 'misconception':
      return (
        <>
          <RichTextField
            label={name('오해')}
            value={block.wrong}
            maxLength={LIMITS.wrong}
            allow={FIELD_RUNS.misconception}
            rows={2}
            onChange={(wrong) => onChange({ ...block, wrong })}
          />
          <RichTextField
            label={name('바로잡기')}
            value={block.correction}
            maxLength={LIMITS.correction}
            allow={FIELD_RUNS.misconception}
            rows={3}
            onChange={(correction) => onChange({ ...block, correction })}
          />
        </>
      )

    case 'list':
      return (
        <>
          <label className={styles.checkField}>
            <input
              type="checkbox"
              checked={block.ordered}
              onChange={(event) => onChange({ ...block, ordered: event.target.checked })}
            />
            <span>{name('번호 목록')}</span>
          </label>
          <ListControls
            addLabel="항목 추가"
            canAdd={block.items.length < LIMITS.listItems}
            onAdd={() => onChange({ ...block, items: [...block.items, ''] })}
          >
            {block.items.map((item, index) => (
              <div key={index} className={styles.row}>
                <RichTextField
                  label={name(`${index + 1}번째 항목`)}
                  value={item}
                  maxLength={LIMITS.listItem}
                  allow={FIELD_RUNS.list}
                  rows={2}
                  onChange={(value) =>
                    onChange({ ...block, items: replaceAt(block.items, index, value) })
                  }
                />
                <button
                  type="button"
                  onClick={() => onChange({ ...block, items: removeAt(block.items, index) })}
                  aria-label={name(`${index + 1}번째 항목 삭제`)}
                >
                  삭제
                </button>
              </div>
            ))}
          </ListControls>
        </>
      )

    case 'table':
      return <TableFields block={block} name={name} onChange={onChange} />

    case 'diagram':
      return <DiagramFields block={block} name={name} onChange={onChange} />

    case 'institutionNote':
      return <InstitutionNoteFields block={block} name={name} onChange={onChange} />
  }
}

type TableBlock = Extract<EditableBlock, { type: 'table' }>

/**
 * 비교 표. 열을 더하거나 빼면 모든 행의 칸도 함께 더하고 뺀다. 행마다 칸 수가 열 수와 같아야 저장된다
 * (data-model §5-3).
 */
function TableFields({
  block,
  name,
  onChange,
}: {
  block: TableBlock
  name: (label: string) => string
  onChange: (block: TableBlock) => void
}) {
  return (
    <>
      <TextInput
        label={name('표 제목')}
        value={block.caption}
        max={LIMITS.caption}
        onChange={(caption) => onChange({ ...block, caption })}
      />
      <h4>
        열 ({LIMITS.minColumns}~{LIMITS.maxColumns}개)
      </h4>
      <ListControls
        addLabel="열 추가"
        canAdd={block.columns.length < LIMITS.maxColumns}
        onAdd={() =>
          onChange({
            ...block,
            columns: [...block.columns, ''],
            rows: block.rows.map((row) => [...row, '']),
          })
        }
      >
        <div className={styles.grid}>
          {block.columns.map((column, index) => (
            <div key={index} className={styles.field}>
              <TextInput
                label={name(`${index + 1}번째 열 이름`)}
                value={column}
                max={LIMITS.column}
                onChange={(value) =>
                  onChange({ ...block, columns: replaceAt(block.columns, index, value) })
                }
              />
              <button
                type="button"
                onClick={() =>
                  onChange({
                    ...block,
                    columns: removeAt(block.columns, index),
                    rows: block.rows.map((row) => removeAt(row, index)),
                  })
                }
                aria-label={name(`${index + 1}번째 열 삭제`)}
              >
                열 삭제
              </button>
            </div>
          ))}
        </div>
      </ListControls>
      <h4>행 (최대 {LIMITS.rows}개)</h4>
      <ListControls
        addLabel="행 추가"
        canAdd={block.rows.length < LIMITS.rows}
        onAdd={() => onChange({ ...block, rows: [...block.rows, block.columns.map(() => '')] })}
      >
        {block.rows.map((row, rowIndex) => (
          <div key={rowIndex} className={styles.item}>
            <div className={styles.grid}>
              {row.map((cell, cellIndex) => (
                <RichTextField
                  key={cellIndex}
                  label={name(`${rowIndex + 1}행 ${cellIndex + 1}열`)}
                  value={cell}
                  maxLength={LIMITS.cell}
                  allow={FIELD_RUNS.table}
                  rows={1}
                  onChange={(value) =>
                    onChange({
                      ...block,
                      rows: replaceAt(block.rows, rowIndex, replaceAt(row, cellIndex, value)),
                    })
                  }
                />
              ))}
            </div>
            <div className={styles.controls}>
              <button
                type="button"
                onClick={() => onChange({ ...block, rows: removeAt(block.rows, rowIndex) })}
                aria-label={name(`${rowIndex + 1}행 삭제`)}
              >
                행 삭제
              </button>
            </div>
          </div>
        ))}
      </ListControls>
    </>
  )
}

type DiagramBlock = Extract<EditableBlock, { type: 'diagram' }>

/**
 * 가상 도식. 가로축 칸을 더하거나 빼면 모든 계열의 값도 함께 더하고 뺀다. 계열마다 값 수가 칸 수와 같아야
 * 저장된다(data-model §5-3).
 */
function DiagramFields({
  block,
  name,
  onChange,
}: {
  block: DiagramBlock
  name: (label: string) => string
  onChange: (block: DiagramBlock) => void
}) {
  return (
    <>
      <label className={styles.field}>
        <span>{name('도식 종류')}</span>
        <select
          value={block.kind}
          onChange={(event) =>
            onChange({ ...block, kind: event.target.value === 'line' ? 'line' : 'bar' })
          }
        >
          <option value="bar">막대</option>
          <option value="line">선</option>
        </select>
      </label>
      <TextInput
        label={name('도식 제목')}
        value={block.title}
        max={LIMITS.diagramTitle}
        onChange={(title) => onChange({ ...block, title })}
      />
      <TextInput
        label={name('단위')}
        value={block.unit}
        max={LIMITS.unit}
        onChange={(unit) => onChange({ ...block, unit })}
      />

      <h4>
        가로축 칸 ({LIMITS.minCategories}~{LIMITS.maxCategories}개)
      </h4>
      <ListControls
        addLabel="칸 추가"
        canAdd={block.categories.length < LIMITS.maxCategories}
        onAdd={() =>
          onChange({
            ...block,
            categories: [...block.categories, ''],
            series: block.series.map((series) => ({ ...series, values: [...series.values, 0] })),
          })
        }
      >
        <div className={styles.grid}>
          {block.categories.map((category, index) => (
            <div key={index} className={styles.field}>
              <TextInput
                label={name(`${index + 1}번째 칸`)}
                value={category}
                max={LIMITS.category}
                onChange={(value) =>
                  onChange({ ...block, categories: replaceAt(block.categories, index, value) })
                }
              />
              <button
                type="button"
                onClick={() =>
                  onChange({
                    ...block,
                    categories: removeAt(block.categories, index),
                    series: block.series.map((series) => ({
                      ...series,
                      values: removeAt(series.values, index),
                    })),
                  })
                }
                aria-label={name(`${index + 1}번째 칸 삭제`)}
              >
                칸 삭제
              </button>
            </div>
          ))}
        </div>
      </ListControls>

      <h4>계열 (1~{LIMITS.maxSeries}개)</h4>
      <ListControls
        addLabel="계열 추가"
        canAdd={block.series.length < LIMITS.maxSeries}
        onAdd={() =>
          onChange({
            ...block,
            series: [...block.series, { name: '', values: block.categories.map(() => 0) }],
          })
        }
      >
        {block.series.map((series, seriesIndex) => (
          <div key={seriesIndex} className={styles.item}>
            <TextInput
              label={name(`${seriesIndex + 1}번째 계열 이름`)}
              value={series.name}
              max={LIMITS.seriesName}
              onChange={(value) =>
                onChange({
                  ...block,
                  series: replaceAt(block.series, seriesIndex, { ...series, name: value }),
                })
              }
            />
            <div className={styles.grid}>
              {series.values.map((value, valueIndex) => (
                <NumberField
                  key={valueIndex}
                  className={styles.field}
                  label={name(`${seriesIndex + 1}번째 계열 ${valueIndex + 1}번째 값`)}
                  value={value}
                  onCommit={(next) =>
                    onChange({
                      ...block,
                      series: replaceAt(block.series, seriesIndex, {
                        ...series,
                        values: replaceAt(series.values, valueIndex, next),
                      }),
                    })
                  }
                />
              ))}
            </div>
            <div className={styles.controls}>
              <button
                type="button"
                onClick={() => onChange({ ...block, series: removeAt(block.series, seriesIndex) })}
                aria-label={name(`${seriesIndex + 1}번째 계열 삭제`)}
              >
                계열 삭제
              </button>
            </div>
          </div>
        ))}
      </ListControls>

      <label className={styles.field}>
        <span>
          {name('설명')} (최대 {LIMITS.description}자, 지금 {block.description.length}자)
        </span>
        <textarea
          rows={3}
          value={block.description}
          onChange={(event) => onChange({ ...block, description: event.target.value })}
        />
      </label>
    </>
  )
}

type InstitutionNoteBlock = Extract<EditableBlock, { type: 'institutionNote' }>

/**
 * 제도 안내. 기준 시점은 날짜 하나이며 비우면 `null`이다. 출처는 이름과 주소가 모두 비면 `null`이다 — 둘 중
 * 하나만 쓴 출처는 그대로 보내 서버가 판정하게 한다.
 */
function InstitutionNoteFields({
  block,
  name,
  onChange,
}: {
  block: InstitutionNoteBlock
  name: (label: string) => string
  onChange: (block: InstitutionNoteBlock) => void
}) {
  const source: DraftSourceRef = block.source ?? { label: '', url: '' }
  const setSource = (next: DraftSourceRef) =>
    onChange({ ...block, source: next.label === '' && next.url === '' ? null : next })

  return (
    <>
      <RichTextField
        label={name('내용')}
        value={block.content}
        maxLength={LIMITS.note}
        allow={FIELD_RUNS.institutionNote}
        rows={4}
        onChange={(content) => onChange({ ...block, content })}
      />
      <label className={styles.field}>
        <span>{name('기준 시점')}</span>
        <input
          type="date"
          value={block.asOf ?? ''}
          onChange={(event) =>
            onChange({ ...block, asOf: event.target.value === '' ? null : event.target.value })
          }
        />
      </label>
      <div className={styles.row}>
        <label className={styles.field}>
          <span>{name('출처 이름')}</span>
          <input
            type="text"
            value={source.label}
            onChange={(event) => setSource({ ...source, label: event.target.value })}
          />
        </label>
        <label className={styles.field}>
          <span>{name('출처 주소')}</span>
          <input
            type="url"
            value={source.url}
            onChange={(event) => setSource({ ...source, url: event.target.value })}
          />
        </label>
      </div>
    </>
  )
}
