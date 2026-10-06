import { aliasSuffix } from './firstOccurrences'
import styles from './ArticleBody.module.css'

interface TermDefinitionProps {
  label: string
  /** 처음 나오는 자리일 때만 넘긴다. 그 밖에는 빈 배열이다 */
  aliases: readonly string[]
  meaning: string
  reading: string
  caveat: string
}

/**
 * 새 기준 용어 블록(013 FR-008, data-model §5-3).
 *
 * <p>용어 이름(처음 나오는 자리면 다른 이름), '뜻'·'읽는 법' 이름표를 단 두 문장, ⚠️를 앞에 둔 한계 문장을
 * 보인다. 문장은 받은 그대로다. ⚠️는 그림 글자라 스크린 리더가 '주의'로 읽게 이름을 붙인다.
 */
export function TermDefinition({ label, aliases, meaning, reading, caveat }: TermDefinitionProps) {
  return (
    <section className={styles.termCard} aria-label={`용어 ${label}`}>
      <p className={styles.termName}>
        {label}
        {aliases.length > 0 ? aliasSuffix(aliases) : null}
      </p>
      <dl className={styles.termLines}>
        {meaning ? (
          <div>
            <dt>뜻</dt>
            <dd>{meaning}</dd>
          </div>
        ) : null}
        {reading ? (
          <div>
            <dt>읽는 법</dt>
            <dd>{reading}</dd>
          </div>
        ) : null}
      </dl>
      {caveat ? (
        <p className={styles.caveat}>
          <span role="img" aria-label="주의">
            ⚠️
          </span>{' '}
          {caveat}
        </p>
      ) : null}
    </section>
  )
}
