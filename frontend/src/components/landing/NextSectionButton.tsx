import { ArrowDown } from 'lucide-react'
import { useReducedMotion } from 'motion/react'
import styles from '@/pages/LandingPage.module.css'

/**
 * 다음 이야기로 내려가는 화살표. 섹션 번호가 서 있는 옆 여백의 맨 아래에 둔다.
 *
 * <p><b>내려갈 수 있다는 것을 알려 주는 장치가 첫 화면에만 있었다.</b> 첫 화면에는 '시장 흐름
 * 보기' 버튼이 있어 다음으로 이어지지만, 그 뒤 섹션들은 스크롤 말고는 다음이 있다는 신호가
 * 없었다. 한 섹션이 화면을 가득 채우는 구성이라 끝인지 더 있는지가 화면만 보고는 구분되지
 * 않는다.
 *
 * <p>옆 여백에 두는 이유는 본문을 밀지 않기 위해서다. 아래에 쌓으면 그만큼 섹션이 길어져
 * 한 화면에 담으려던 것이 다시 깨진다.
 *
 * <p>마지막 섹션에는 붙이지 않는다 — 갈 곳이 없는 화살표는 눌러도 아무 일이 없다.
 */
export function NextSectionButton({ targetId, label }: { targetId: string; label: string }) {
  const reducedMotion = useReducedMotion()

  const moveToNext = () => {
    document.querySelector(`#${targetId}`)?.scrollIntoView({
      behavior: reducedMotion ? 'auto' : 'smooth',
      block: 'start',
    })
  }

  return (
    <button type="button" className={styles.nextSection} onClick={moveToNext} aria-label={label}>
      <ArrowDown aria-hidden="true" size={18} strokeWidth={1.7} />
    </button>
  )
}
