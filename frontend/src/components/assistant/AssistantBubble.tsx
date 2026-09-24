import { X } from 'lucide-react'
import { type FormEvent, useEffect, useRef, useState } from 'react'
import { askAssistant, isAssistantRequestCancelled, normalizeAssistantError } from '@/api/assistant'
import { Button } from '@/components/ui/button'
import type { AssistantAnswer, AssistantEvidenceTab } from '@/types/assistant'
import type { AssistantPage } from './assistantPageContext'
import styles from './assistant.module.css'

export const QUESTION_MAX_LENGTH = 300
export const TRANSFER_NOTICE = '질문과 답변에 필요한 화면 자료가 외부 AI 처리 서비스로 전달됩니다.'
export const EXAMPLE_QUESTIONS = [
  '거래비용은 어떻게 구성됐어?',
  '비용을 빼고 얼마가 남았어?',
  '가장 크게 줄었던 때 무엇을 보유했어?',
] as const

/** 005 결과 탭 이름. 근거 카드가 "어느 탭에 있는 값인지"를 이 말로 보인다. */
const TAB_LABELS: Record<AssistantEvidenceTab, string> = {
  summary: '요약',
  analysis: '결과 분석',
  settings: '설정·근거',
  trades: '전체 거래 내역',
  loss: '손실이 커진 때',
}

type Failure = { message: string; retryable: boolean; retryAfterSeconds: number | null }

type Exchange = {
  id: number
  question: string
  answer: AssistantAnswer | null
  failure: Failure | null
}

function retryWording(retryAfterSeconds: number | null): string {
  if (retryAfterSeconds === null) return '잠시 후 다시 시도할 수 있어요.'
  if (retryAfterSeconds < 90) return `${retryAfterSeconds}초 뒤에 다시 시도할 수 있어요.`
  const at = new Date(Date.now() + retryAfterSeconds * 1000)
  const hh = String(at.getHours()).padStart(2, '0')
  const mm = String(at.getMinutes()).padStart(2, '0')
  return `${hh}:${mm} 이후에 다시 시도할 수 있어요.`
}

/**
 * 말풍선 안쪽(spec 015 FR-004·FR-010·FR-012·FR-015).
 *
 * <p>맥락 표시, 예시 질문 두셋, 300자 입력 하나. 답변 종류를 고르는 조작은 없다. 첫 전송 전에 외부 전달 안내를
 * 보이고 전송은 사용자가 제출할 때만 한다. 답의 숫자는 서버가 채운 값이고 근거 카드는 답에 쓰인 것만이다.
 * 각 질문은 독립이다 — 이력은 이 말풍선이 열려 있는 동안만 보이고 저장하지 않는다.
 *
 * <p>화면(맥락)이 바뀌면 진행 중 요청을 취소하고 이력을 비운다. 요청 번호로 늦게 온 이전 답을 버린다.
 */
export function AssistantBubble({
  page,
  openTab,
  onClose,
  autoFocus = false,
}: {
  page: AssistantPage
  openTab: (tab: string) => void
  onClose: () => void
  autoFocus?: boolean
}) {
  const [question, setQuestion] = useState('')
  const [exchanges, setExchanges] = useState<Exchange[]>([])
  const [sending, setSending] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const sequenceRef = useRef(0)

  useEffect(() => {
    // 맥락이 바뀌면(다른 결과로 이동) 이전 화면의 요청과 답을 버린다(FR-015).
    setExchanges([])
    setQuestion('')
    setSending(false)
    return () => {
      controllerRef.current?.abort()
      controllerRef.current = null
      sequenceRef.current += 1
    }
  }, [page.kind, page.publicToken])

  useEffect(() => {
    if (autoFocus) textareaRef.current?.focus()
  }, [autoFocus])

  const send = async (text: string) => {
    const trimmed = text.trim()
    if (!trimmed || sending) return
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    const sequence = ++sequenceRef.current
    const id = sequence
    setExchanges((current) => [...current, { id, question: trimmed, answer: null, failure: null }])
    setQuestion('')
    setSending(true)
    try {
      const answer = await askAssistant(
        trimmed,
        { kind: page.kind, publicToken: page.publicToken },
        controller.signal,
      )
      if (sequenceRef.current !== sequence) return
      setExchanges((current) => current.map((item) => (item.id === id ? { ...item, answer } : item)))
    } catch (error) {
      if (isAssistantRequestCancelled(error) || sequenceRef.current !== sequence) return
      const failure = normalizeAssistantError(error)
      setExchanges((current) =>
        current.map((item) =>
          item.id === id
            ? {
                ...item,
                failure: {
                  message: failure.message,
                  retryable: failure.retryable,
                  retryAfterSeconds: failure.status === 429 ? failure.retryAfterSeconds : null,
                },
              }
            : item,
        ),
      )
    } finally {
      if (sequenceRef.current === sequence) setSending(false)
    }
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    void send(question)
  }

  const sent = exchanges.length > 0

  return (
    <div className={styles.bubble}>
      <div className={styles.head}>
        <span className={styles.context}>기준 결과에 질문</span>
        <button type="button" className={styles.close} aria-label="말풍선 닫기" onClick={onClose}>
          <X size={18} aria-hidden="true" />
        </button>
      </div>
      {!sent ? <p className={styles.notice}>{TRANSFER_NOTICE}</p> : null}
      {!sent ? (
        <ul className={styles.examples} aria-label="예시 질문">
          {EXAMPLE_QUESTIONS.map((example) => (
            <li key={example}>
              <button type="button" className={styles.example} onClick={() => void send(example)}>
                {example}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <ol className={styles.log} aria-live="polite" aria-label="질문과 답">
        {exchanges.map((exchange) => (
          <li key={exchange.id} className={styles.exchange}>
            <p className={styles.question}>{exchange.question}</p>
            {exchange.answer === null && exchange.failure === null ? (
              <p className={styles.pending} role="status">
                답을 만들고 있어요…
              </p>
            ) : null}
            {exchange.answer ? (
              <>
                <p className={styles.answer} data-status={exchange.answer.status}>
                  {exchange.answer.answer}
                </p>
                {exchange.answer.evidence.length > 0 ? (
                  <ul className={styles.evidence} aria-label="근거">
                    {exchange.answer.evidence.map((fact) => (
                      <li key={fact.id}>
                        <button
                          type="button"
                          className={styles.card}
                          onClick={() => openTab(fact.tab)}
                          aria-label={`${fact.label} ${fact.value}. ${TAB_LABELS[fact.tab] ?? fact.tab} 탭 열기`}
                        >
                          <span className={styles.cardLabel}>{fact.label}</span>
                          <strong className={styles.cardValue}>{fact.value}</strong>
                          <span className={styles.cardMeta}>
                            {fact.asOf ? <time dateTime={fact.asOf}>{fact.asOf}</time> : null}
                            <span>{TAB_LABELS[fact.tab] ?? fact.tab}</span>
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </>
            ) : null}
            {exchange.failure ? (
              <div className={styles.failure} role="alert">
                <p>
                  {exchange.failure.message}
                  {exchange.failure.retryAfterSeconds !== null
                    ? ` ${retryWording(exchange.failure.retryAfterSeconds)}`
                    : ''}
                </p>
                {exchange.failure.retryable ? (
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    onClick={() => void send(exchange.question)}
                  >
                    다시 시도
                  </Button>
                ) : null}
              </div>
            ) : null}
          </li>
        ))}
      </ol>
      <form className={styles.form} onSubmit={submit}>
        <textarea
          ref={textareaRef}
          className={styles.input}
          aria-label="질문"
          placeholder="이 결과에 대해 물어보세요"
          rows={2}
          maxLength={QUESTION_MAX_LENGTH}
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault()
              void send(question)
            }
          }}
        />
        <div className={styles.formRow}>
          <span className={styles.counter} aria-live="off">
            {question.length}/{QUESTION_MAX_LENGTH}
          </span>
          <Button
            type="submit"
            variant="secondary"
            size="sm"
            disabled={sending || question.trim().length === 0}
          >
            {sending ? '답을 기다리는 중' : '보내기'}
          </Button>
        </div>
      </form>
    </div>
  )
}
