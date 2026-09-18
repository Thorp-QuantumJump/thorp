import type { RegimeDataAsOf } from '@/types/regime'
import { Disclosure } from './Disclosure'

interface Props { releases: RegimeDataAsOf['newerReleases']; context: string }

/** 종합 기준월 뒤에 발표된 자료는 검색 입력과 분리해 알린다. */
export function NewerReleases({ releases, context }: Props) {
  if (releases.length === 0) return null
  return (
    <Disclosure label="더 최신인 개별 발표" context={context}>
      <ul>
        {releases.map((item) => (
          <li key={`${item.indicatorId}-${item.month}-${item.publishedAt}`}>
            {item.indicator} · 대상월 {item.month ?? '—'} · 발표일 {item.publishedAt ?? '—'}
          </li>
        ))}
      </ul>
    </Disclosure>
  )
}
