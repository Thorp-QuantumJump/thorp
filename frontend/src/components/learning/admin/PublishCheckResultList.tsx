import type { PublishCheckFailure, PublishCheckLocation } from '@/types/learningAdmin'
import styles from './PublishCheckResultList.module.css'

/**
 * 게시 전 검사에 걸린 항목을 보인다(013 contracts/learning-admin-api §6, T065).
 *
 * <p><b>서버가 보낸 `message`를 그대로 보인다.</b> 거절 문장은 버전이 붙은 서버 규칙 파일이
 * 소유하므로(research R7) 화면이 문장을 새로 짓거나 줄이면 규칙과 안내가 갈라진다. 이 컴포넌트가
 * 짓는 문장은 위치 하나뿐이며, 그것도 서버가 주지 않는 값이라 여기서 만든다.
 *
 * <p><b>걸린 항목을 모두 보인다.</b> 서버가 첫 항목에서 멈추지 않으므로 화면도 접거나 줄이지
 * 않는다. 한 번에 다 보여야 운영자가 고치고 다시 누르기를 되풀이하지 않는다.
 */

interface PublishCheckResultListProps {
  /** 서버가 준 순서 그대로다. 화면이 다시 정렬하지 않는다 */
  failures: PublishCheckFailure[]
  /** 이 판을 검사한 규칙 파일의 버전. 없으면 보이지 않는다 */
  checkVersion?: string | null
}

/** 서버가 주지 않는 사람이 읽을 위치다. `part`에 따라 따라오는 필드가 다르다(계약 §6). */
function describeLocation(location: PublishCheckLocation): string {
  switch (location.part) {
    case 'title':
      return '제목'
    case 'question':
      return '질문'
    case 'example':
      return '예시'
    case 'block':
      // `REQUIRED_FIELDS`의 '문단 없음'은 특정 블록이 아니라 본문 전체를 가리켜 `index`가 없다.
      return location.index === undefined ? '본문' : `본문 ${location.index + 1}번째 블록`
    case 'term':
      return location.termId === undefined ? '용어' : `용어 ${location.termId}`
  }
}

export function PublishCheckResultList({ failures, checkVersion }: PublishCheckResultListProps) {
  if (failures.length === 0) return null

  return (
    <section className={styles.result} aria-labelledby="publish-check-title">
      <h3 id="publish-check-title">게시 전 검사에 걸린 항목</h3>
      <ul className={styles.list} role="alert">
        {failures.map((failure, index) => (
          // 같은 검사가 여러 위치에서 걸릴 수 있어 `check`만으로는 키가 겹친다.
          <li key={index} className={styles.item}>
            <span className={styles.check}>{failure.check}</span>
            <span className={styles.location}>{describeLocation(failure.location)}</span>
            {failure.matched === undefined ? null : (
              <span className={styles.matched}>걸린 표현: {failure.matched}</span>
            )}
            <span className={styles.message}>{failure.message}</span>
          </li>
        ))}
      </ul>
      {checkVersion ? <p className={styles.version}>검사 규칙 {checkVersion}</p> : null}
    </section>
  )
}
