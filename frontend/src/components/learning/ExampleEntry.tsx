import { Link, useNavigate } from 'react-router-dom'
import { LEARNING_EXAMPLE_STATE_KEY } from '@/diagnosis/learningExample'
import type { LearningExample } from '@/types/learning'
import styles from './ExampleEntry.module.css'

interface ExampleEntryProps {
  articleId: string
  example: LearningExample
}

const SECTOR_SELECTION_PATH = '/diagnosis/sectors'

/**
 * 글의 예시로 섹터·비중 선택(002)을 시작한다(013 FR-003, term-help-ui §4).
 *
 * <p><b>값을 싣기만 한다.</b> 덮어쓸지 묻는 것과 값을 검증해 채우는 것은 받는 쪽(002
 * `diagnosis/learningExample.ts`)이 한다. state 키도 002가 정한 이름을 그대로 가져다 쓴다.
 *
 * <p><b>`weightTenths`를 변환하지 않는다.</b> 공개 조회와 002 계약의 단위가 같다. 섹터 이름은 받는
 * 쪽이 자기 목록에서 다시 찾으므로 싣지 않는다.
 *
 * <p>넘길 수 없는 예시(`available=false`)면 버튼을 그리지 않고, 까닭 한 문장과 빈 섹터 선택 화면으로
 * 가는 연결을 보인다(US2 시나리오 3).
 */
export function ExampleEntry({ articleId, example }: ExampleEntryProps) {
  const navigate = useNavigate()

  if (!example.available) {
    return (
      <div className={styles.unavailable}>
        <p>{unavailableMessage(example)}</p>
        <Link to={SECTOR_SELECTION_PATH}>빈 섹터 선택 화면으로 가기</Link>
      </div>
    )
  }

  const start = () => {
    void navigate(SECTOR_SELECTION_PATH, {
      state: {
        [LEARNING_EXAMPLE_STATE_KEY]: {
          source: 'learning',
          topicId: articleId,
          selected: example.sectors.map(({ code, weightTenths }) => ({ code, weightTenths })),
        },
      },
    })
  }

  return (
    <button type="button" className={styles.start} onClick={start}>
      이 예시로 비중 비교하기
    </button>
  )
}

function unavailableMessage(example: LearningExample): string {
  switch (example.unavailableReason) {
    case 'SECTOR_NOT_IN_MAP':
      return `예시에 쓴 섹터 중 지금 섹터 목록에 없는 것이 있어 이 예시로 시작할 수 없습니다: ${example.missingSectorCodes.join(', ')}`
    case 'SECTOR_MAP_NOT_READY':
      return '지금은 섹터 목록을 확인할 수 없어 이 예시로 시작할 수 없습니다.'
    default:
      return '지금은 이 예시로 시작할 수 없습니다.'
  }
}
