/**
 * 화면 맥락 도우미(spec 015) 응답 타입. 계약은 `specs/015-context-assistant/contracts/assistant-api.md` §1,
 * 예시는 `docs/specs/assistant-api/examples/`다.
 */

export type AssistantPageKind = 'RESULT'

/** `GET /assistant/capabilities`. 꺼져 있으면 `scopes`가 비고 화면은 버튼을 그리지 않는다. */
export type AssistantCapabilities = {
  enabled: boolean
  scopes: AssistantPageKind[]
}

/** `POST /assistant/questions` 요청의 `context`. 종류마다 식별자 하나뿐이다. */
export type AssistantContext = {
  kind: 'RESULT'
  publicToken: string
}

export type AssistantAnswerStatus = 'ANSWERED' | 'INSUFFICIENT_EVIDENCE' | 'OUT_OF_SCOPE'

/** 결과 화면 탭 id(005). 근거 카드는 이 탭으로 이동한다. */
export type AssistantEvidenceTab = 'summary' | 'analysis' | 'settings' | 'trades' | 'loss'

export type AssistantEvidence = {
  /** 이 응답 안에서만 뜻이 있는 `Fn` */
  id: string
  label: string
  /** 서버가 표기까지 끝낸 값. 답 안의 숫자는 모두 여기서 왔다 */
  value: string
  asOf: string | null
  tab: AssistantEvidenceTab
}

export type AssistantAnswer = {
  status: AssistantAnswerStatus
  answer: string
  /** 답에 실제로 쓰인 근거만. `ANSWERED`가 아니면 빈 배열 */
  evidence: AssistantEvidence[]
  page: { kind: AssistantPageKind }
}
