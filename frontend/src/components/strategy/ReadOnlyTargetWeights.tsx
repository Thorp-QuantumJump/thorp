import { Link } from 'react-router-dom'
import { formatWeightTenths } from '@/diagnosis/weights'
import styles from './DetailSettingsPanel.module.css'

/** 표 한 줄. 섹터 이름은 002의 섹터 목록에서, 비중은 002가 확정한 값에서 온다. */
export type TargetWeightRow = {
  sectorId: string
  name: string
  /** 0~1000 정수(0.1%p). `1000`이 100%다 */
  weightTenths: number
}

/** 002 섹터·비중 단계의 주소. 페이지의 되돌아가기 안내와 같은 곳이다. */
export const SECTOR_WEIGHT_STEP_PATH = '/diagnosis/sectors'

interface ReadOnlyTargetWeightsProps {
  /** 002가 확정한 비중. 앞 단계의 선택을 읽지 못했으면 `null`이다 */
  rows: readonly TargetWeightRow[] | null
}

/**
 * 사용자가 정한 목표 비중을 읽기 전용으로 보인다(FR-003).
 *
 * <p><b>이 화면에는 비중을 고칠 수단이 없다.</b> 비중은 002가 소유하고, 여기서 고칠 수 있게 하면 같은
 * 값의 원본이 둘이 된다. 고치려면 섹터·비중 단계로 돌아가라고 안내하고 그리로 가는 연결을 둔다.
 *
 * <p>표시 형식은 002 화면과 같은 `formatWeightTenths`다 — 같은 비중이 두 화면에서 다른 숫자로 보이지
 * 않게 한다.
 */
export function ReadOnlyTargetWeights({ rows }: ReadOnlyTargetWeightsProps) {
  return (
    <section className={styles.weights} aria-labelledby="target-weights-title">
      <h3 id="target-weights-title" className={styles.subTitle}>
        목표 비중 (읽기 전용)
      </h3>

      {rows === null ? (
        <p className={styles.note}>앞 단계에서 정한 비중을 읽지 못했습니다.</p>
      ) : (
        <table className={styles.weightTable}>
          <thead>
            <tr>
              <th scope="col">섹터</th>
              <th scope="col">비중</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.sectorId}>
                <th scope="row">{row.name}</th>
                <td>{formatWeightTenths(row.weightTenths)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <p className={styles.note}>
        이 화면에서는 비중을 고칠 수 없습니다. 고치려면{' '}
        <Link to={SECTOR_WEIGHT_STEP_PATH}>섹터·비중 단계로 돌아가세요</Link>.
      </p>
    </section>
  )
}
