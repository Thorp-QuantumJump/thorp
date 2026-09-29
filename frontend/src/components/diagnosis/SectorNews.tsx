import { Newspaper } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { fetchNewsSectors } from '@/api/marketNews'
import { NewsDigestSection } from '@/components/marketflow/NewsDigestSection'
import { groupNewsSectors } from '@/diagnosis/newsSectorGroups'
import type { NewsSector } from '@/types/marketNews'
import styles from './SectorNews.module.css'

/*
  투자 대상 고르기의 뉴스 카드('지금과 비슷했던 과거 시장' 옆, 002 FR-019, 012 FR-001·FR-005). 기본은 '전체'로 최신 뉴스
  묶음을 보이고, 섹터를 고르면 그 섹터와 연결된 뉴스로 좁힌다.

  - 칩은 012의 섹터 목록(`GET /market-news/sectors`)으로 그린다. 투자 대상 섹터와 뉴스 전용 섹터가 함께 온다 — 뉴스는 투자 대상과
    대응하지 않아도 보여야 하기 때문이다(012 Session 2026-09-25). 섹터 이름을 여기 상수로 두지 않는다.
  - 칩은 분류(기술·제조·산업·금융…) 아래에 한 번 더 묶는다. 첫 줄은 '전체'와 분류이고, 분류를 누르면 그 분류의
    섹터 칩이 아랫줄에 펼쳐진다. 분류만 눌러서는 뉴스가 좁혀지지 않는다(`newsSectorGroups` 참고).
  - 목록을 받지 못하면 '전체'만 남긴다. 뉴스 자체는 막히지 않는다.
  - 여기서 고른 섹터는 뉴스 범위만 정하며 투자 대상 선택이나 경제환경 검색에 영향을 주지 않는다(010 FR-004).
*/

export function SectorNews() {
  const [sectors, setSectors] = useState<NewsSector[]>([])
  const [sectorId, setSectorId] = useState<string | null>(null)
  const [groupId, setGroupId] = useState<string | null>(null)
  const groups = useMemo(() => groupNewsSectors(sectors), [sectors])
  const openGroup = groups.find((group) => group.id === groupId) ?? null

  useEffect(() => {
    const controller = new AbortController()
    fetchNewsSectors(controller.signal)
      .then((list) => setSectors(list))
      .catch(() => {
        if (!controller.signal.aborted) setSectors([])
      })
    return () => controller.abort()
  }, [])

  return (
    <section className={styles.card} aria-labelledby="sector-news-title">
      <h2 id="sector-news-title" className={styles.title}>
        <Newspaper size={16} aria-hidden="true" />
        뉴스
      </h2>
      <div className={styles.groups} role="group" aria-label="분류별로 보기">
        <button
          type="button"
          className={styles.group}
          aria-pressed={openGroup === null}
          onClick={() => {
            setGroupId(null)
            setSectorId(null)
          }}
        >
          전체
        </button>
        {groups.map((group) => (
          <button
            key={group.id}
            type="button"
            className={styles.group}
            aria-pressed={openGroup?.id === group.id}
            onClick={() => setGroupId(group.id)}
          >
            {group.name}
          </button>
        ))}
      </div>
      {openGroup ? (
        <div className={styles.filter} role="group" aria-label={`${openGroup.name} 섹터별로 보기`}>
          {openGroup.sectors.map((sector) => (
            <button
              key={sector.sectorId}
              type="button"
              className={styles.chip}
              aria-pressed={sectorId === sector.sectorId}
              onClick={() => setSectorId(sector.sectorId)}
            >
              {sector.name}
            </button>
          ))}
        </div>
      ) : null}
      <NewsDigestSection sectorCode={sectorId} />
    </section>
  )
}
