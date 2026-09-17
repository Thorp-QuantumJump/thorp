import { Link } from 'react-router-dom'
import styles from './ResultUnavailableNotice.module.css'

export const EXPIRED_MESSAGE = '결과가 더 이상 남아 있지 않습니다.'
export const NOT_FOUND_MESSAGE = '주소가 올바르지 않아 결과를 찾을 수 없습니다.'

/**
 * 결과를 열 수 없는 주소의 안내(contracts "오류").
 *
 * <p>만료(410)와 잘못된 주소(404·400)는 사용자가 할 수 있는 일이 달라 문구를 나눈다. 만료된 결과는
 * 같은 조건으로 다시 분석할 수 있지만, 조건이 남아 있지 않으므로 전략 선택 화면으로 잇는다.
 *
 * <p><b>어느 쪽도 숫자 자리나 그래프 자리를 그리지 않는다</b>(SC-008).
 */
export function ResultUnavailableNotice({ reason }: { reason: 'expired' | 'notFound' }) {
  return (
    <section className={styles.notice} aria-labelledby="result-unavailable-title">
      <h1 id="result-unavailable-title" className={styles.title}>
        {reason === 'expired' ? EXPIRED_MESSAGE : NOT_FOUND_MESSAGE}
      </h1>
      {reason === 'expired' ? (
        <>
          <p className={styles.copy}>
            로그인하지 않고 만든 결과는 정해진 시간이 지나면 지워집니다. 같은 조건으로 다시 분석할 수
            있습니다.
          </p>
          <Link className={styles.action} to="/strategy">
            같은 조건으로 다시 분석하기
          </Link>
        </>
      ) : (
        <>
          <p className={styles.copy}>받은 주소가 끝까지 복사되었는지 확인해 주세요.</p>
          <Link className={styles.action} to="/diagnosis/sectors">
            새로 분석하기
          </Link>
        </>
      )}
    </section>
  )
}
