import { LoaderCircle, SendHorizontal, X } from 'lucide-react'
import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { askAssistant, isAssistantRequestCancelled, normalizeAssistantError } from '@/api/assistant'
import { Button } from '@/components/ui/button'
import type { AssistantAnswer } from '@/types/assistant'
import type { AssistantPage } from './assistantPageContext'
import styles from './assistant.module.css'

export const QUESTION_MAX_LENGTH = 300
export const TRANSFER_NOTICE = '백테스트 결과에 대해서 질문해보세요.'
export const EXAMPLE_QUESTIONS = [
  '거래비용은 어떻게 구성됐어?',
  '비용을 빼고 얼마가 남았어?',
  '가장 크게 줄었던 때 무엇을 보유했어?',
] as const

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

export type AssistantConversation = {
  exchanges: Exchange[]
  sending: boolean
  send: (text: string) => Promise<void>
}

/**
 * 한 화면의 질문·답 이력과 진행 중 요청. 말풍선이 아니라 플로팅 버튼이 들고 있어서 말풍선을 닫았다 열어도 남는다.
 * 저장하지 않으므로 새로 고치면 사라지고, 화면(맥락)이 바뀌면 진행 중 요청을 취소하고 이력을 비운다(FR-015).
 * 요청 번호로 늦게 온 이전 답을 버린다.
 */
export function useAssistantConversation(page: AssistantPage): AssistantConversation {
  const [exchanges, setExchanges] = useState<Exchange[]>([])
  const [sending, setSending] = useState(false)
  const controllerRef = useRef<AbortController | null>(null)
  const sequenceRef = useRef(0)

  useEffect(() => {
    setExchanges([])
    setSending(false)
    return () => {
      controllerRef.current?.abort()
      controllerRef.current = null
      sequenceRef.current += 1
    }
  }, [page.kind, page.publicToken])

  const send = useCallback(
    async (text: string) => {
      const trimmed = text.trim()
      if (!trimmed || controllerRef.current) return
      const controller = new AbortController()
      controllerRef.current = controller
      const sequence = ++sequenceRef.current
      const id = sequence
      setExchanges((current) => [...current, { id, question: trimmed, answer: null, failure: null }])
      setSending(true)
      try {
        const answer = await askAssistant(
          trimmed,
          { kind: page.kind, publicToken: page.publicToken },
          controller.signal,
        )
        if (sequenceRef.current !== sequence) return
        setExchanges((current) =>
          current.map((item) => (item.id === id ? { ...item, answer } : item)),
        )
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
        if (sequenceRef.current === sequence) {
          controllerRef.current = null
          setSending(false)
        }
      }
    },
    [page.kind, page.publicToken],
  )

  return { exchanges, sending, send }
}

/**
 * 말풍선 안쪽(spec 015 FR-004·FR-010·FR-012·FR-015).
 *
 * <p>맥락 표시, 예시 질문 두셋, 300자 입력 하나. 답변 종류를 고르는 조작은 없다. 첫 전송 전에 안내를 보이고 전송은
 * 사용자가 제출할 때만 한다. 답의 숫자는 서버가 채운 값이다. 이력은 `useAssistantConversation`이 들고 있다.
 */
export function AssistantBubble({
  conversation,
  onClose,
  autoFocus = false,
}: {
  conversation: AssistantConversation
  onClose: () => void
  autoFocus?: boolean
}) {
  const { exchanges, sending, send } = conversation
  const [question, setQuestion] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (autoFocus) textareaRef.current?.focus()
  }, [autoFocus])

  // 보내기 버튼과 Enter 가 같은 길이다. 보내면서 입력을 비운다.
  const sendTyped = () => {
    if (sending) return
    setQuestion('')
    void send(question)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    sendTyped()
  }

  const sent = exchanges.length > 0

  return (
    <div className={styles.bubble}>
      <div className={styles.head}>
        <span className={styles.context}>결과 도우미</span>
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
                <LoaderCircle className={styles.spinner} aria-hidden="true" />
                답을 만들고 있어요…
              </p>
            ) : null}
            {exchange.answer ? (
              <>
                <p className={styles.answer} data-status={exchange.answer.status}>
                  {exchange.answer.answer}
                </p>
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
              sendTyped()
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
            size="icon-sm"
            aria-label={sending ? '답을 기다리는 중' : '보내기'}
            disabled={sending || question.trim().length === 0}
          >
            <SendHorizontal aria-hidden="true" />
          </Button>
        </div>
      </form>
    </div>
  )
}
