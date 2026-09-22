import { Play, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { ProvisionalBadge } from './ProvisionalBadge'
import type { ParamSearchPreview } from '@/types/paramSearch'
import { formatCount, formatSeconds } from '@/utils/paramSearchLabels'
import styles from './PreflightPanel.module.css'

interface PreflightPanelProps {
  preview: ParamSearchPreview
  /** 한 동작으로 탐색을 시작한다. 이 패널의 유일한 행동이다. */
  onRun: () => void
  running: boolean
  /**
   * 지금 실행할 수 없는 이유. 응답이 아니라 화면 사정(로그인 필요 등)으로 막을 때만 쓴다.
   * 주면 실행을 막고 그 문장을 그대로 보인다 — 막힌 이유를 적지 않고 버튼만 끄지 않는다.
   */
  blockedReason?: string | null
  /**
   * `blockedReason`을 푸는 행동. **이유만 적고 길을 내지 않으면 막다른 길이다** — 로그인이
   * 필요하다고 알리면서 로그인으로 가는 통로를 주지 않으면 사용자는 화면을 떠나 헤더를 찾아야
   * 하고, 돌아오면 고른 대상이 사라져 있다.
   *
   * <p>한도·조합 수처럼 응답이 정한 차단에는 붙지 않는다. 그건 범위를 좁혀야 풀리는 것이라
   * 누를 것이 따로 없다.
   */
  blockedAction?: ReactNode
}

/** 한도 항목의 이름과 값. 필드 이름은 계약의 것이고 전략 지식이 아니다. */
function limitRows(limits: ParamSearchPreview['limits']) {
  return [
    { key: 'maxCandidates', label: '후보 수 상한', value: `${formatCount(limits.maxCandidates)}개` },
    {
      key: 'timeLimitSeconds',
      label: '실행 시간 한도',
      value: formatSeconds(limits.timeLimitSeconds),
    },
    {
      key: 'dispatchWindow',
      label: '동시 실행 수',
      value: `${formatCount(limits.dispatchWindow)}개`,
    },
    {
      key: 'consecutiveFailureLimit',
      label: '연속 실패 한도',
      value: `${formatCount(limits.consecutiveFailureLimit)}회`,
    },
  ]
}

/**
 * 실행 전 확인 화면(SC-001, FR-005a).
 *
 * <p><b>네 항목만 보이고 행동 버튼은 하나다.</b> 대상 전략·탐색 범위 요약·예상 작업량·적용 한도를
 * 읽고 실행을 누르면 끝이며, <b>후보 숫자를 입력받지 않는다</b> — 몇 개를 돌릴지는 등록표와 한도가
 * 정한다. 범위를 좁히고 싶으면 상세 변경(`SpaceEditor`)으로 간다.
 *
 * <p>화면에 전략 목록을 두지 않는다. 여기 보이는 전략은 "고를 수 있는 것들"이 아니라 <b>이번 탐색의
 * 대상</b>이고, 이름은 응답의 `strategies[].name`에서만 읽는다(FR-025).
 *
 */
export function PreflightPanel({
  preview,
  onRun,
  running,
  blockedReason = null,
  blockedAction = null,
}: PreflightPanelProps) {
  const { strategies, spaceSummary, workload, limits } = preview

  // 응답이 정한 차단이 먼저다. 한도를 넘은 채로 로그인해 봐야 여전히 실행되지 않는다.
  const responseBlock = limits.exceeded
    ? '실행 예정 후보가 한도를 넘습니다. 상세 변경에서 후보값을 줄여 주세요.'
    : workload.validCombinations === 0
      ? '실행할 수 있는 조합이 없습니다. 대상 섹터나 후보값을 바꿔 주세요.'
      : null
  const blockMessage = responseBlock ?? blockedReason
  const blocked = blockMessage !== null

  return (
    <section className={styles.panel} aria-labelledby="preflight-title">
      <header className={styles.header}>
        <h2 id="preflight-title">실행 전 확인</h2>
        <p>
          등록표 {preview.registryVersion} 기준입니다. 아래 내용 그대로 한 번에 실행합니다.
        </p>
      </header>

      <dl className={styles.items}>
        {/* 1. 대상 전략 — 이름은 응답에서만 읽는다 */}
        <div className={styles.item}>
          <dt>대상 전략</dt>
          <dd>
            {strategies.length === 0 ? (
              <span className={styles.empty}>등록표에서 찾은 전략이 없습니다.</span>
            ) : (
              <ul className={styles.strategyList}>
                {strategies.map((strategy) => (
                  <li key={strategy.strategyId}>
                    <span>{strategy.name}</span>
                    {strategy.executable ? null : (
                      <em className={styles.notExecutable}>실행 미지원</em>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </dd>
        </div>

        {/* 2. 탐색 범위 요약 — 축 라벨·단위도 응답의 것이다 */}
        <div className={styles.item}>
          <dt>탐색 범위</dt>
          <dd>
            {spaceSummary.length === 0 ? (
              <span className={styles.empty}>탐색할 범위가 없습니다.</span>
            ) : (
              <ul className={styles.spaceList}>
                {spaceSummary.map((space) => (
                  <li key={space.strategyId}>
                    <span className={styles.spaceName}>{space.strategyName}</span>
                    <span className={styles.spaceAxes}>
                      {space.axes.length === 0
                        ? '탐색 축 없음'
                        : space.axes
                            .map(
                              (axis) =>
                                `${axis.label} ${formatCount(axis.values.length)}개${
                                  axis.unit ? ` (${axis.unit})` : ''
                                }`,
                            )
                            .join(' · ')}
                    </span>
                    <span className={styles.spaceCount}>
                      조합 {formatCount(space.combinations)}개
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </dd>
        </div>

        {/* 3. 예상 작업량 — 제외된 조합은 사유와 함께 보인다 */}
        <div className={styles.item}>
          <dt>예상 작업량</dt>
          <dd>
            <p className={styles.workload}>
              <strong>{formatCount(workload.validCombinations)}개</strong> 실행 예정
              <span className={styles.workloadTotal}>
                {` / 전체 조합 ${formatCount(workload.totalCombinations)}개`}
              </span>
            </p>
            {workload.excludedCombinations > 0 && (
              <ul className={styles.exclusions}>
                <li className={styles.exclusionHead}>
                  {`계산 전 제외 ${formatCount(workload.excludedCombinations)}개`}
                </li>
                {workload.exclusionReasons.map((reason) => (
                  <li key={reason.code}>
                    {`${reason.message} (${formatCount(reason.count)}개)`}
                  </li>
                ))}
              </ul>
            )}
          </dd>
        </div>

        {/* 4. 적용 한도 — provisional 에 든 항목에만 임시값 표시가 붙는다 */}
        <div className={styles.item}>
          <dt>적용 한도</dt>
          <dd>
            <ul className={styles.limitList}>
              {limitRows(limits).map((row) => (
                <li key={row.key}>
                  <span className={styles.limitLabel}>
                    {row.label}
                    {limits.provisional.includes(row.key) && (
                      <ProvisionalBadge label={row.label} />
                    )}
                  </span>
                  <span className={styles.limitValue}>{row.value}</span>
                </li>
              ))}
            </ul>
          </dd>
        </div>
      </dl>

      {preview.commonPeriod && (
        <p className={styles.period}>
          {`평가 구간 ${preview.commonPeriod.start} ~ ${preview.commonPeriod.end}`}
          {preview.commonPeriod.shortenedBy
            ? ` · ${preview.commonPeriod.shortenedBy} 때문에 짧아졌습니다`
            : null}
        </p>
      )}

      {blocked && (
        <div className={styles.blocked} role="alert">
          <p>
            <TriangleAlert size={15} aria-hidden="true" />
            {blockMessage}
          </p>
          {responseBlock === null && blockedAction ? (
            <span className={styles.blockedAction}>{blockedAction}</span>
          ) : null}
        </div>
      )}

      <button
        type="button"
        className={styles.run}
        onClick={onRun}
        disabled={running || blocked}
      >
        <Play size={15} fill="currentColor" aria-hidden="true" />
        {running ? '실행 접수 중' : '설정 조합 자동 비교 실행'}
      </button>
    </section>
  )
}
