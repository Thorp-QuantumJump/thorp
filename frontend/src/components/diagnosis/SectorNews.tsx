import { Newspaper } from 'lucide-react'
import { useState } from 'react'
import { NewsDigestSection } from '@/components/marketflow/NewsDigestSection'
import type { SectorSummary } from '@/types/sector'
import styles from './SectorNews.module.css'

/*
  투자 대상 고르기 오른쪽의 시장 흐름 뉴스(002 FR-019, 012 FR-001). 기본은 '전체'로 최신 뉴스 묶음을 보이고,
  섹터를 고르면 그 섹터와 연결된 뉴스로 좁힌다. 여기서 고른 섹터는 뉴스 범위만 정하며 투자 대상 선택이나
  경제환경 검색에 영향을 주지 않는다(012 FR-001, 010 FR-004).
*/

interface SectorNewsProps {
  sectors: readonly Pick<SectorSummary, 'code' | 'name'>[]
}

export function SectorNews({ sectors }: SectorNewsProps) {
  const [sectorCode, setSectorCode] = useState<string | null>(null)
  const options = [{ code: null, name: '전체' }, ...sectors]

  return (
    <section className={styles.card} aria-labelledby="sector-news-title">
      <h2 id="sector-news-title" className={styles.title}>
        <Newspaper size={16} aria-hidden="true" />
        시장 흐름 뉴스
      </h2>
      <div className={styles.filter} role="group" aria-label="섹터별로 보기">
        {options.map((option) => (
          <button
            key={option.code ?? 'all'}
            type="button"
            className={styles.chip}
            aria-pressed={sectorCode === option.code}
            onClick={() => setSectorCode(option.code)}
          >
            {option.name}
          </button>
        ))}
      </div>
      <NewsDigestSection sectorCode={sectorCode} />
    </section>
  )
}
