import { Link } from 'react-router-dom'

export function ComparisonKindBadge({
  kind,
  searchId,
}: {
  kind: 'BASIC' | 'AUTO_SEARCH'
  searchId: string | null
}) {
  if (kind === 'BASIC') return null
  return (
    <p>
      <span>자동 탐색 후보 비교</span>
      {searchId ? (
        <>
          {' '}
          ·{' '}
          <Link to={'/param-search?searchId=' + encodeURIComponent(searchId)}>
            탐색 묶음으로 돌아가기
          </Link>
        </>
      ) : null}
    </p>
  )
}
