import type { MyRecordRegime } from '@/types/myRecords'
import { WITHDRAWN_REASON } from './recordLabels'
import styles from './records.module.css'

export const NO_LINK_NOTICE = '아직 연결 정보가 없습니다 — 전략 연결은 이후 기능에서 제공됩니다.'

const MODE_LABEL: Record<string, string> = { FIELD: '분야 검색', COMPOSITE: '종합 비교' }
const ORIGIN_LABEL: Record<string, string> = {
  T_G: '분야의 최신 기준월(T_g)',
  T_ALL: '종합의 최신 기준월(T_all)',
  USER_SPECIFIED: '직접 지정한 달',
}
const ENTRY_LABEL: Record<string, string> = {
  MARKET_FLOW: '시장 흐름에서',
  STRATEGY_RESULT: '전략 결과에서',
}

/**
 * 경제환경 항목의 펼쳐 보는 층(spec 006 T039, FR-012).
 *
 * <p>값은 전부 `regime_search_log`에서 읽은 것이다. 연결 정보(연결한 섹터·전략·사후 평가 구간·가용성 필터)는 011이 만들
 * 값이고 아직 표가 없어 상세에 키가 없다 — 그 자리에는 고정 문장을 두고 <b>임의 값을 채우지 않는다</b>(research R9,
 * 원칙 VI). 모르는 코드는 코드 그대로 보인다.
 */
export function RegimeRecordPanel({ regime }: { regime: MyRecordRegime }) {
  return (
    <>
      {regime.withdrawn ? (
        <p role="status" className={styles.notice}>
          {WITHDRAWN_REASON}
        </p>
      ) : null}
      <dl className={styles.facts}>
        <dt>검색 방식</dt>
        <dd>{MODE_LABEL[regime.mode] ?? regime.mode}</dd>
        {regime.fieldId ? (
          <>
            <dt>분야</dt>
            <dd>{regime.fieldId}</dd>
          </>
        ) : null}
        <dt>기준월</dt>
        <dd>{regime.baselineMonth.slice(0, 7)}</dd>
        <dt>기준월 근거</dt>
        <dd>{ORIGIN_LABEL[regime.baselineOrigin] ?? regime.baselineOrigin}</dd>
        <dt>들어온 경로</dt>
        <dd>{ENTRY_LABEL[regime.entryPath] ?? regime.entryPath}</dd>
        <dt>연결 정보</dt>
        <dd>{NO_LINK_NOTICE}</dd>
      </dl>
    </>
  )
}
