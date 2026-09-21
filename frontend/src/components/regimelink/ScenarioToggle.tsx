import { useState } from 'react'
import type { RegimeLinkView } from '@/types/regimeLink'
import { freshCashNote, scenarioToggleLabel } from './fixedCopy'
import { resultSlotKind } from './resultSlot'
import { ResultSlotState } from './ResultSlotState'

interface ScenarioToggleProps {
  /** 켤 때 한 번만 부른다. 미리 돌려 두지 않는다(FR-010). */
  onEnable: () => void
  /** 켠 뒤 받아 온 현금 시작 링크들. 비어 있으면 아직 접수·계산 중이다. */
  freshLinks: RegimeLinkView[]
}

/**
 * '그날 현금으로 새로 시작' 토글 (T062).
 *
 * 끈 상태에서는 아무 요청도 하지 않는다. 결과가 오면 연속 운용과 **구분해** 보이고 다른 이유를
 * 고정 문구 한 줄로 붙인다.
 */
export function ScenarioToggle({ onEnable, freshLinks }: ScenarioToggleProps) {
  const [enabled, setEnabled] = useState(false)

  function toggle() {
    setEnabled((current) => {
      if (!current) onEnable()
      return !current
    })
  }

  return (
    <section aria-labelledby="regime-link-scenario-title">
      <h4 id="regime-link-scenario-title">시나리오</h4>
      <label>
        <input type="checkbox" checked={enabled} onChange={toggle} />
        {scenarioToggleLabel}
      </label>
      {enabled && (
        <div data-scenario="FRESH_CASH">
          <p>{freshCashNote}</p>
          {freshLinks.map((link) => (
            <div key={link.linkId}>
              <ResultSlotState link={link} />
              {resultSlotKind(link) === 'READY' && link.result?.window63 && (
                <dl>
                  <dt>수익률</dt>
                  <dd>{link.result.window63.return}</dd>
                  <dt>최대 하락 폭</dt>
                  <dd>{link.result.window63.maxDrawdown}</dd>
                </dl>
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
