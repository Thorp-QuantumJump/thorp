import type { RegimeLinkBundleChanged } from '@/types/regimeLink'

/**
 * 비교 묶음 변경 알림 (T077 · FR-021).
 *
 * **유지율을 그릴 필드가 타입에 없다.** `RegimeLinkBundleChanged`는 추가·제외된 사례와 제외 수만
 * 담으므로 비율을 보이려 해도 읽을 값이 없다(계약 §6).
 */
export function BundleChangeNotice({ change }: { change: RegimeLinkBundleChanged }) {
  return (
    <section aria-labelledby="regime-link-bundle-change" role="status">
      <p id="regime-link-bundle-change">
        비교 묶음이 달라져 볼 수 있는 사례가 바뀌었습니다.
      </p>
      {change.removed.length > 0 && (
        <div>
          <h3>빠진 사례</h3>
          <ul>
            {change.removed.map((endMonth) => (
              <li key={`removed-${endMonth}`}>{endMonth}</li>
            ))}
          </ul>
        </div>
      )}
      {change.added.length > 0 && (
        <div>
          <h3>더해진 사례</h3>
          <ul>
            {change.added.map((endMonth) => (
              <li key={`added-${endMonth}`}>{endMonth}</li>
            ))}
          </ul>
        </div>
      )}
      <p>{`제외된 사례 ${change.excludedCount}개`}</p>
    </section>
  )
}
