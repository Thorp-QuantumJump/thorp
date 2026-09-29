import { Newspaper } from 'lucide-react'
import { useEffect, useState } from 'react'
import { fetchNewsSectors } from '@/api/marketNews'
import { NewsDigestSection } from '@/components/marketflow/NewsDigestSection'
import type { NewsSector } from '@/types/marketNews'
import styles from './SectorNews.module.css'

/*
  투자 대상 고르기의 시장 흐름 뉴스('지금과 비슷했던 과거 시장' 옆, 002 FR-019, 012 FR-001·FR-005). 기본은 '전체'로 최신 뉴스
  묶음을 보이고, 섹터를 고르면 그 섹터와 연결된 뉴스로 좁힌다.

  - 칩은 012의 섹터 목록(`GET /market-news/sectors`)으로 그린다. 투자 대상 섹터와 뉴스 전용 섹터가 함께 온다 — 뉴스는 투자 대상과
    대응하지 않아도 보여야 하기 때문이다(012 Session 2026-09-25). 섹터 이름을 여기 상수로 두지 않는다.
  - 목록을 받지 못하면 '전체'만 남긴다. 뉴스 자체는 막히지 않는다.
  - 여기서 고른 섹터는 뉴스 범위만 정하며 투자 대상 선택이나 경제환경 검색에 영향을 주지 않는다(010 FR-004).
*/

export function SectorNews() {
  const [sectors, setSectors] = useState<NewsSector[]>([])
  const [sectorId, setSectorId] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    fetchNewsSectors(controller.signal)
      .then((list) => setSectors(list))
      .catch(() => {
        if (!controller.signal.aborted) setSectors([])
      })
    return () => controller.abort()
  }, [])

  const options = [{ sectorId: null, name: '전체' }, ...sectors]

  return (
    <section className={styles.card} aria-labelledby="sector-news-title">
      <h2 id="sector-news-title" className={styles.title}>
        <Newspaper size={16} aria-hidden="true" />
        뉴스
      </h2>
      <div className={styles.filter} role="group" aria-label="섹터별로 보기">
        {options.map((option) => (
          <button
            key={option.sectorId ?? 'all'}
            type="button"
            className={styles.chip}
            aria-pressed={sectorId === option.sectorId}
            onClick={() => setSectorId(option.sectorId)}
          >
            {option.name}
          </button>
        ))}
      </div>
      <NewsDigestSection sectorCode={sectorId} />
    </section>
  )
}
