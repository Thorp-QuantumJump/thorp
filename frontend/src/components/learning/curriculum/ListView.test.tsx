import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { cssDeclarations, cssMediaDeclarations } from '@/test/cssRule'
import { releasedCurriculum } from '@/test/learningFixtures'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { StageView } from '@/types/learning'
import { ListView } from './ListView'

/** 013 T138·T141. 목록 보기(FR-023·FR-031, design-notes §3.2, quickstart 8-4·11-1). */

const CSS = 'components/learning/curriculum/ListView.module.css'
const BOUNDARY = '지금 공개된 단계는 1·2단계입니다. 3~6단계는 준비 중입니다.'

describe('ListView', () => {
  it('넓은 화면 표는 단계마다 tbody와 rowgroup 구분 줄을 두고 읽는 순서대로 번호·제목·질문을 보인다', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} />)

    const table = screen.getByRole('table')
    expect(table.querySelectorAll('tbody[data-stage-anchor]')).toHaveLength(6)
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

  it('상태 열 없이 그 글의 용어 개수만 보이고, 용어가 없는 글은 비운다. 상태 표시는 나의 학습(B13)이 더한다', () => {
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
    expect(table.textContent).not.toMatch(/완료|읽는 중|안 읽음|완주|편 중/)
  })

  it('준비 중 단계는 번호·이름·수준·한 줄 설명과 준비 중·사유, 번호 없는 이전 판 줄만 있다(FR-031)', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} />)

    const table = screen.getByRole('table')
    const stageThree = table.querySelector('tbody[data-stage-anchor="3"]') as HTMLElement
    const group = within(stageThree).getByRole('rowheader')
    expect(group.textContent).toContain('3단계·물때표 읽기·입문~초급')
    expect(group.textContent).toContain('준비 중')
    expect(group.textContent).toContain('아직 공개되지 않은 단계입니다.')
    expect(group.textContent).toContain('합성 단계 설명 셋이다.')
    expect(group.textContent).toContain('이 단계의 글을 모두 준비하면 한 번에 공개합니다.')
    const previous = within(stageThree).getByRole('link', { name: '닻줄 길이' }).closest('tr')!
    expect(previous.textContent).toContain('이전 판')
    expect(previous.querySelector('td:nth-child(2)')?.textContent).toBe('')

    const stageSix = table.querySelector('tbody[data-stage-anchor="6"]') as HTMLElement
    expect(within(stageSix).queryAllByRole('link')).toHaveLength(0)
    expect(stageSix.textContent).toContain('합성 단계 설명 여섯이다.')
    expect(stageSix.textContent).not.toMatch(/\d+편|약 \d+분/)
  })

  it('글이 0편인 공개 단계는 한 줄 설명과 다시 준비한다는 사유를 보인다', () => {
    const stages: StageView[] = releasedCurriculum.stages.map((stage) =>
      stage.number === 2 ? { ...stage, status: 'REPREPARING', articles: [] } : stage,
    )

    renderWithRouter(<ListView stages={stages} />)

    const stageTwo = screen.getByRole('table').querySelector('tbody[data-stage-anchor="2"]') as HTMLElement
    expect(stageTwo.textContent).toContain('합성 단계 설명 둘이다.')
    expect(stageTwo.textContent).toContain('이 단계의 글을 다시 준비하고 있습니다. 글이 게시되면 이 자리에 보입니다.')
    expect(stageTwo.textContent).not.toContain('이 단계의 글을 모두 준비하면 한 번에 공개합니다.')
    expect(stageTwo.textContent).not.toContain('아직 공개되지 않은 단계입니다.')
  })

  it('공개된 단계가 끝나는 자리에 공개 경계 한 줄을 둔다. 표와 좁은 화면 목록 모두 같은 자리다(FR-031)', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} />)

    const table = screen.getByRole('table')
    const boundary = within(table).getByText(BOUNDARY)
    const stageTwo = table.querySelector('tbody[data-stage-anchor="2"]')!
    const stageThree = table.querySelector('tbody[data-stage-anchor="3"]')!
    expect(stageTwo.compareDocumentPosition(boundary) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(boundary.compareDocumentPosition(stageThree) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    // 좁은 화면 목록의 항목은 단계 여섯뿐이고, 경계 한 줄은 3단계 항목의 맨 앞에 있다.
    const list = screen.getByRole('list', { name: '배우기 글 목록' })
    const items = Array.from(list.children)
    expect(items).toHaveLength(6)
    expect(items[2].firstElementChild?.textContent).toBe(BOUNDARY)
  })

  it('노선은 공개 경계 앞에서 끝나고 경계 줄에서 점선이 새로 시작한다', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} />)

    const table = screen.getByRole('table')
    // 마지막 공개 글 줄에는 다음 줄로 가는 아래 노선이 없다.
    const lastReleasedRow = table.querySelector('tbody[data-stage-anchor="2"]')!.lastElementChild!
    expect(lastReleasedRow.querySelector('[class*="railTop"]')).not.toBeNull()
    expect(lastReleasedRow.querySelector('[class*="railBottom"]')).toBeNull()
    const boundaryRail = within(table).getByText(BOUNDARY).closest('tr')!.querySelector('td')!
    expect(Array.from(boundaryRail.querySelectorAll('[data-kind]')).map((rail) => rail.getAttribute('data-kind'))).toEqual([
      'preparing',
    ])
    expect(
      cssDeclarations(CSS, ".railBottom[data-kind='preparing']"),
    ).toMatch(/repeating-linear-gradient/)
  })

  it('여섯 단계를 모두 공개했으면 공개 경계가 없다', () => {
    const stages: StageView[] = releasedCurriculum.stages.map((stage) =>
      stage.status === 'PREPARING' ? { ...stage, status: 'REPREPARING', previousEditionArticles: [] } : stage,
    )

    renderWithRouter(<ListView stages={stages} />)

    expect(screen.queryByText(/지금 공개된 단계는/)).toBeNull()
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

    // Vitest는 CSS를 적용하지 않으므로 화면 폭 규칙이 미디어 블록 안에 실제로 있는지 파일에서 본다.
    expect(cssDeclarations(CSS, '.narrowList')).toMatch(/display:\s*none/)
    expect(cssMediaDeclarations(CSS, '(max-width: 719px)', '.table')).toMatch(/display:\s*none/)
    expect(cssMediaDeclarations(CSS, '(max-width: 719px)', '.narrowList')).toMatch(/display:\s*block/)
    // 가로 스크롤을 두지 않는다.
    const css = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'ListView.module.css'), 'utf-8')
    expect(css).not.toMatch(/overflow-x:\s*(auto|scroll)/)
  })

  it('단계 자리로 옮겨 와 초점을 받는 구분 줄은 단계 제목 표지와 2px 초록 외곽선을 갖는다(design-notes §6)', () => {
    renderWithRouter(<ListView stages={releasedCurriculum.stages} />)

    for (const cell of within(screen.getByRole('table')).getAllByRole('rowheader')) {
      expect(cell.getAttribute('tabindex')).toBe('-1')
      expect(cell.hasAttribute('data-stage-heading')).toBe(true)
    }
    // 표의 구분 줄(th)과 좁은 화면의 h3가 한 규칙을 쓴다. 규칙의 마지막 선택자로 찾는다.
    expect(cssDeclarations(CSS, '.groupHead h3:focus-visible')).toMatch(
      /outline:\s*var\(--thorp-focus-outline-compact\)/,
    )
    expect(readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'ListView.module.css'), 'utf-8')).toMatch(
      /\.groupRow th:focus-visible,\s*\n\.groupHead h3:focus-visible\s*\{/,
    )
  })
})
