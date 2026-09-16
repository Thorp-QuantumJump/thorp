import { useMemo } from 'react'
import {
  readStoredSelection,
  type DroppedSelection,
  type SelectionRestoreProblem,
} from '@/diagnosis/selectionStorage'
import type { SectorSummary } from '@/types/sector'

/**
 * 002가 확정한 섹터·비중을 003이 이어받는 **유일한 통로**다.
 *
 * <p>인계 규약의 원본은 002가 소유하는
 * `specs/002-sector-weight-selection/contracts/selection-handoff.md` 1절이고, 이 파일은 그 계약을
 * 다시 정의하지 않는다. <b>페이로드를 직접 읽지도 않는다</b> — 저장 키·형식 번호·불변식 검사·
 * 카탈로그 대조는 002의 `diagnosis/selectionStorage`가 이미 갖고 있으므로 그것을 부른다. 003이
 * 같은 파서를 따로 두면 002가 형식을 올릴 때 한쪽만 따라가고, 어긋난 사실은 사용자가 잘못된
 * 비중으로 분석한 뒤에야 드러난다.
 *
 * <p><b>종목은 페이로드에 없다.</b> 계약이 "003이 필요로 하는 섹터 이름·대표 ETF 이름은 003이
 * `GET /sectors`로 직접 받는다"고 정했다. 그래서 이 훅은 섹터 목록을 받아 코드로 대표 ETF를
 * 찾는다. 이름을 페이로드에 실으면 대응표 버전이 바뀌었을 때 어느 쪽이 기준인지 흐려진다.
 *
 * <p><b>비중은 0.1%p 단위 정수 그대로 옮긴다.</b> 계약의 값 표현 규칙이 소수·문자열을 금지한다 —
 * 합계 판정이 `sum === 1000`이라는 정수 비교이므로 여기서 단위를 바꾸면 받는 쪽 판정이 흔들린다.
 */

/**
 * 003이 진행하려면 필요한 최소 섹터 수.
 *
 * 이 제품이 하는 일은 배분 방식이 만드는 차이를 견주는 것이고, **견주려면 나눌 곳이 둘은 있어야
 * 한다.** 섹터가 하나면 어떤 배분 전략을 골라도 그 하나에 전액이 가므로 다섯 전략이 모두 같은
 * 결과를 낸다. spec Edge Cases가 두 개 미만을 "이전 단계를 다시 완료하도록 안내"하는 상태로 둔
 * 까닭이다.
 */
export const MINIMUM_SECTOR_COUNT = 2

/** 고른 섹터 하나와 그 대표 상품, 그리고 사용자가 정한 비중. */
export type SectorSelectionItem = {
  sectorId: string
  instrumentId: string
  /** 0~1000 정수(0.1%p). `1000`이 100%다. 합계가 1000이라는 보장은 없다(계약 불변식 3). */
  weightTenths: number
}

/** 002가 확정한 섹터 선택 한 벌. */
export type SectorSelection = {
  /** 값을 만들 당시의 대응표 버전. 분석 요청의 재현 기록에 함께 싣는다(원칙 VIII). */
  mapVersion: string
  items: SectorSelectionItem[]
}

/**
 * 앞 단계의 상태.
 *
 * <p><b>"아직 알 수 없다"와 "마치지 않았다"를 가른다.</b> 섹터 목록을 받기 전에 후자로 접으면
 * 화면이 판정하지 않은 결과를 말하게 되고, 조회가 끝나기도 전에 "앞 단계를 마쳐 주세요"가
 * 번쩍인다(원칙 VI).
 */
export type SectorSelectionState =
  /** 섹터 목록을 아직 받지 못해 판정할 수 없다. */
  | { status: 'pending' }
  /**
   * 앞 단계를 마치지 않았다. 선택이 없는 경우, 저장된 자료가 손상된 경우, 고른 섹터가 두 개
   * 미만인 경우가 모두 이쪽이다 — spec Edge Cases가 셋을 한 문장으로 묶었다.
   */
  | {
      status: 'incomplete'
      /** 읽지 못한 까닭. 없으면 그냥 고른 것이 모자란 것이다. */
      problem: SelectionRestoreProblem | null
      dropped: DroppedSelection[]
    }
  | {
      status: 'ready'
      selection: SectorSelection
      /**
       * 보관되어 있었으나 선택에 남지 못한 항목.
       *
       * **비어 있지 않은데 화면이 말하지 않으면 안 된다.** 계약의 "읽는 쪽의 의무"가 빠진 항목을
       * 조용히 버리지 말라고 정한다(원칙 VI).
       */
      dropped: DroppedSelection[]
    }

/**
 * 앞 단계에서 정한 섹터·비중.
 *
 * `sectors`는 `GET /sectors`로 받은 목록이며, 아직 받지 못했으면 `null`을 넘긴다. 돌려주는
 * `items`의 길이가 곧 등록표 조회의 `universeSize`다 — 화면이 그 수를 따로 세지 않는다.
 *
 * <p>계산은 `sectors`가 바뀔 때만 다시 한다. 호출자는 조회 결과를 그대로 넘겨 같은 목록이 매
 * 렌더 새 배열이 되지 않게 한다.
 */
export function useSectorSelection(
  sectors: readonly SectorSummary[] | null,
): SectorSelectionState {
  return useMemo(() => {
    if (sectors === null) return { status: 'pending' }

    const restored = readStoredSelection(sectors)
    const snapshot = restored.snapshot

    if (snapshot === null || snapshot.selectedCodes.length < MINIMUM_SECTOR_COUNT) {
      return { status: 'incomplete', problem: restored.problem, dropped: restored.dropped }
    }

    const instrumentBySector = new Map(
      sectors.map((sector) => [sector.code, sector.representativeEtf.instrumentId] as const),
    )

    const items: SectorSelectionItem[] = []
    for (const code of snapshot.selectedCodes) {
      const instrumentId = instrumentBySector.get(code)
      // 002가 카탈로그에 없는 코드를 이미 걸러 내므로 여기에 걸릴 일은 없다. 그래도 단언으로
      // 넘기지 않는 것은, 걸린다면 그것이 두 모듈의 전제가 어긋났다는 뜻이고 그때 지어낸 종목
      // ID로 분석 요청이 나가는 편보다 앞 단계로 되돌리는 편이 낫기 때문이다.
      if (instrumentId === undefined) {
        return { status: 'incomplete', problem: 'unreadable', dropped: restored.dropped }
      }
      items.push({
        sectorId: code,
        instrumentId,
        weightTenths: snapshot.weightTenths[code] ?? 0,
      })
    }

    return {
      status: 'ready',
      selection: { mapVersion: snapshot.mapVersion, items },
      dropped: restored.dropped,
    }
  }, [sectors])
}
