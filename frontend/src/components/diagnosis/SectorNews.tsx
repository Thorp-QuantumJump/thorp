import { Newspaper } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { fetchNewsSectors } from '@/api/marketNews'
import { InlineHelp } from '@/components/common/InlineHelp'
import { NewsDigestSection } from '@/components/marketflow/NewsDigestSection'
import { groupNewsSectors } from '@/diagnosis/newsSectorGroups'
import type { NewsSector } from '@/types/marketNews'
import styles from './SectorNews.module.css'

/*
  시장 흐름의 뉴스 카드('지금과 비슷했던 과거 시장' 옆, 009 FR-019, 012 FR-001·FR-005). 기본은 '전체'로 최신 뉴스
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
      {/*
        갱신 주기는 제목 옆 i 표시 뒤에 둔다. 주기는 백엔드 수집 설정(thorp.news.collection-interval, 기본 PT15M)을 따르며
        API가 주기를 내주지 않아 여기 문장에 적는다 — 설정을 바꾸면 이 문장도 함께 고친다. 회차가 끝난 뒤 쉬는
        방식이라 '약'을 붙이고, 오래된 뉴스를 '실시간'이라 부르지 않는다(012 FR-014).
      */}
      <div className={styles.head}>
        <h2 id="sector-news-title" className={styles.title}>
          <Newspaper size={16} aria-hidden="true" />
          뉴스
        </h2>
        <InlineHelp label="뉴스 갱신" mark="i" side="bottom">
          <p>뉴스는 약 15분마다 새로 모아 같은 사건끼리 묶습니다.</p>
          <p>수집이 늦어지면 마지막으로 모은 뉴스를 그대로 보여 드립니다.</p>
        </InlineHelp>
      </div>
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
