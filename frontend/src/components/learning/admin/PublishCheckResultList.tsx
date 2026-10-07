import { useId } from 'react'
import type { PublishCheckFailure, PublishCheckLocation } from '@/types/learningAdmin'
import { describeField } from './draftLabels'
import styles from './PublishCheckResultList.module.css'

/**
 * 게시 전 검사에 걸린 항목을 보인다(013 contracts/learning-admin-api §6·§10·§11, T065·T167).
 *
 * <p><b>서버가 보낸 `message`를 그대로 보인다.</b> 거절 문장은 버전이 붙은 서버 규칙 파일이
 * 소유하므로(research R7) 화면이 문장을 새로 짓거나 줄이면 규칙과 안내가 갈라진다. 이 컴포넌트가
 * 짓는 문장은 위치 하나뿐이며, 그것도 서버가 주지 않는 값이라 여기서 만든다. 검사 이름(`check`)도
 * 서버 값 그대로 보인다.
 *
 * <p><b>걸린 항목을 모두 보인다.</b> 서버가 첫 항목에서 멈추지 않으므로 화면도 접거나 줄이지
 * 않는다. 한 번에 다 보여야 운영자가 고치고 다시 누르기를 되풀이하지 않는다.
 *
 * <p>(2판) 단계 미리 검사는 글마다 이 목록을 그린다. 제목 `id`는 그릴 때마다 새로 지어 겹치지 않게 한다.
 */

interface PublishCheckResultListProps {
  /** 서버가 준 순서 그대로다. 화면이 다시 정렬하지 않는다 */
  failures: PublishCheckFailure[]
  /** 이 판을 검사한 규칙 파일의 버전. 없으면 보이지 않는다 */
  checkVersion?: string | null
  /** 목록 제목. 단계 미리 검사는 글 제목을 넣는다 */
  title?: string
  /**
   * 목록을 경고로 알리는가. 한 번에 여러 목록을 그리는 단계 미리 검사는 끄고 바깥에서 한 번만 알린다.
   * 목록마다 경고가 나가면 화면 읽기 프로그램이 같은 일을 여러 번 읽는다
   */
  announce?: boolean
}

function withField(base: string, field: string | undefined): string {
  return field === undefined ? base : `${base} · ${describeField(field)}`
}

/**
 * 확인 문제 위치. 문제 식별자가 있으면 그것을, 식별자 없는 새 문제면 순번을, 둘 다 없으면 확인 문제
 * 전체(문제 수 검사 등)를 가리킨다(data-model §8-4).
 */
function describeQuiz(location: PublishCheckLocation): string {
  const question =
    location.questionId !== undefined
      ? `확인 문제 ${location.questionId}`
      : location.index !== undefined
        ? `확인 문제 ${location.index + 1}번째(새 문제)`
        : '확인 문제'
  const choice =
    location.choiceId === undefined ? question : `${question} 보기 ${location.choiceId}`
  return withField(choice, location.field)
}

/** 서버가 주지 않는 사람이 읽을 위치다. `part`에 따라 따라오는 필드가 다르다(data-model §8-4). */
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
      return withField(
        location.index === undefined ? '본문' : `본문 ${location.index + 1}번째 블록`,
        location.field,
      )
    case 'term':
      return withField(
        location.termId === undefined ? '용어' : `용어 ${location.termId}`,
        location.field,
      )
    case 'quiz':
      return describeQuiz(location)
    case 'article':
      return '글 전체'
  }
}

export function PublishCheckResultList({
  failures,
  checkVersion,
  title = '게시 전 검사에 걸린 항목',
  announce = true,
}: PublishCheckResultListProps) {
  const titleId = useId()
  if (failures.length === 0) return null

  return (
    <section className={styles.result} aria-labelledby={titleId}>
      <h3 id={titleId}>{title}</h3>
      <ul className={styles.list} role={announce ? 'alert' : undefined}>
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
