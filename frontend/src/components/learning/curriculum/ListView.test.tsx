import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { releasedCurriculum } from '@/test/learningFixtures'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { ArticleProgress } from './articleProgress'
import { ListView } from './ListView'

/** 013 T138·T141. 목록 보기(FR-023, design-notes §3.2, quickstart 11-1). */

const progress = new Map<string, ArticleProgress>([
  ['harbor-notes', { state: 'done', masteredTermCount: 1 }],
  ['dock-fees', { state: 'reading', percent: 62, masteredTermCount: 0 }],
  ['tide-reading', { state: 'unread', masteredTermCount: 0 }],
  ['swell-reading', { state: 'unread', masteredTermCount: 0 }],
])

describe('ListView', () => {
  it('넓은 화면 표는 단계마다 tbody와 rowgroup 구분 줄을 두고 읽는 순서대로 번호·제목·질문을 보인다', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} />)

    const table = screen.getByRole('table')
    expect(table.querySelectorAll('tbody')).toHaveLength(6)
    const groups = within(table).getAllByRole('rowheader')
    expect(groups.map((cell) => cell.getAttribute('scope'))).toEqual(Array(6).fill('rowgroup'))
    expect(groups[0].textContent).toContain('1단계·항구 기초·입문')
    expect(groups[0].textContent).toContain('2편 · 약 9분')

    const links = within(table).getAllByRole('link')
    expect(links.slice(0, 4).map((link) => link.getAttribute('href'))).toEqual([
      '/learn/harbor-notes',
      '/learn/dock-fees',
      '/learn/tide-reading',
      '/learn/swell-reading',
    ])
    const firstRow = links[0].closest('tr')!
    expect(firstRow.textContent).toContain('1-1')
    expect(firstRow.textContent).toContain('같은 배가 다른 항구에서 다르게 보이는 이유는?')
  })

  it('상태를 모르면 상태 열 없이 그 글의 용어 개수만 보이고, 용어가 없는 글은 비운다', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} />)

    const table = screen.getByRole('table')
    expect(within(table).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      '번호',
      '제목과 질문',
      '읽기',
      '이 글의 용어',
    ])
    const rows = within(table).getAllByRole('link').map((link) => link.closest('tr')!)
    expect(rows[0].lastElementChild?.textContent).toBe('2개')
    expect(rows[1].lastElementChild?.textContent).toBe('')
    expect(table.textContent).not.toMatch(/완료|읽는 중|안 읽음/)
  })

  it('상태를 받으면 상태 열과 익힌 용어 수, 단계 구분 줄에 단계별 진행을 보인다', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} progress={progress} />)

    const table = screen.getByRole('table')
    const rows = within(table).getAllByRole('link').map((link) => link.closest('tr')!)
    expect(rows[0].textContent).toContain('완료')
    expect(rows[0].textContent).toContain('2개 중 1개 익힘')
    expect(rows[1].textContent).toContain('62% 읽는 중')
    expect(rows[2].textContent).toContain('안 읽음')
    expect(within(table).getAllByRole('rowheader')[0].textContent).toContain('2편 중 1편 읽음')
  })

  it('준비 중 단계는 사유와 번호 없는 이전 판 줄만 있다', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} />)

    const table = screen.getByRole('table')
    const stageThree = table.querySelector('tbody[data-stage-anchor="3"]') as HTMLElement
    expect(stageThree.textContent).toContain('준비 중')
    expect(stageThree.textContent).toContain('이 단계의 글을 모두 준비하면 한 번에 공개합니다.')
    const previous = within(stageThree).getByRole('link', { name: '닻줄 길이' }).closest('tr')!
    expect(previous.textContent).toContain('이전 판')
    expect(previous.querySelector('td:nth-child(2)')?.textContent).toBe('')

    const stageSix = table.querySelector('tbody[data-stage-anchor="6"]') as HTMLElement
    expect(within(stageSix).queryAllByRole('link')).toHaveLength(0)
    expect(stageSix.textContent).not.toMatch(/\d+편|약 \d+분/)
  })

  it('좁은 화면 목록도 같은 글을 같은 순서로 그리고, 720px 미만에서는 표 대신 목록만 보인다', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} />)

    const list = screen.getByRole('list', { name: '배우기 글 목록' })
    const tableLinks = within(screen.getByRole('table')).getAllByRole('link')
    const listLinks = within(list).getAllByRole('link')
    expect(listLinks.map((link) => link.getAttribute('href'))).toEqual(
      tableLinks.map((link) => link.getAttribute('href')),
    )
    expect(within(list).getAllByRole('heading', { level: 3 })).toHaveLength(6)

    // Vitest는 CSS를 적용하지 않으므로 화면 폭 규칙이 실제로 있는지 파일에서 본다. 가로 스크롤을 두지 않는다.
    const css = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'ListView.module.css'), 'utf-8')
    const narrow = css.slice(css.indexOf('@media (max-width: 719px)'))
    expect(narrow).toMatch(/\.table\s*\{\s*display:\s*none;/)
    expect(narrow).toMatch(/\.narrowList\s*\{\s*display:\s*block;/)
    expect(css).not.toMatch(/overflow-x:\s*(auto|scroll)/)
  })
})
