import { useId } from 'react'
import type { DraftQuizChoice, DraftQuizQuestion } from '@/types/learningAdmin'
import styles from './AdminEditor.module.css'

/**
 * V2 글의 확인 문제를 편집한다(013 data-model §6, T170).
 *
 * <p><b>받은 식별자를 그대로 둔다.</b> 문제·보기의 `questionId`·`choiceId`는 서버가 준 값이며 화면이 짓지 않는다.
 * 새로 더한 문제·보기는 `null`로 보내고 저장 응답에서 채워진다(data-model §6-2). 식별자를 지어 보내면 400이다.
 *
 * <p><b>정답은 문제마다 하나를 라디오로 고른다.</b> 서버 모양(`correct` 참·거짓)은 여럿을 담을 수 있지만, 게시
 * 검사가 '정답 하나'를 요구하므로 화면은 처음부터 하나만 고르게 한다. 원고로 들어온 정답 둘은 그대로 보이고
 * 게시 검사가 알려 준다.
 *
 * <p>개수(문제 2~3개, 보기 2~5개)와 길이는 안내만 하고 판정은 서버가 한다. 추가 단추는 저장 상한에서 멈춘다.
 */

interface QuizEditorProps {
  questions: DraftQuizQuestion[]
  onChange: (questions: DraftQuizQuestion[]) => void
}

const MAX_QUESTIONS = 3
const MAX_CHOICES = 5
const PROMPT_MAX = 200
const CHOICE_MAX = 100
const EXPLANATION_MAX = 300

function newQuestion(): DraftQuizQuestion {
  return {
    questionId: null,
    prompt: '',
    choices: [
      { choiceId: null, text: '', correct: true },
      { choiceId: null, text: '', correct: false },
    ],
    explanation: '',
  }
}

export function QuizEditor({ questions, onChange }: QuizEditorProps) {
  const replaceAt = (index: number, next: DraftQuizQuestion) =>
    onChange(questions.map((question, position) => (position === index ? next : question)))

  return (
    <section className={styles.editor} aria-labelledby="quiz-editor-title">
      <h3 id="quiz-editor-title">확인 문제</h3>
      <p className={styles.guide}>
        게시하려면 문제가 2~3개이고, 문제마다 보기 2~5개 가운데 정답이 하나여야 합니다. 정답을 다른
        보기로 바꾸거나 보기를 더하거나 빼면, 게시할 때 그 문제는 이미 답한 사용자에게도 새 문제로
        보입니다. 보기 글자를 고쳐 뜻을 바꾸지 마세요.
      </p>

      <ol className={styles.list}>
        {questions.map((question, index) => (
          <QuestionItem
            key={index}
            number={index + 1}
            question={question}
            onChange={(next) => replaceAt(index, next)}
            onRemove={() => onChange(questions.filter((_, position) => position !== index))}
          />
        ))}
      </ol>

      {questions.length === 0 ? <p className={styles.empty}>확인 문제가 없습니다.</p> : null}

      <div className={styles.add}>
        <button
          type="button"
          onClick={() => onChange([...questions, newQuestion()])}
          disabled={questions.length >= MAX_QUESTIONS}
        >
          문제 추가
        </button>
      </div>
    </section>
  )
}

interface QuestionItemProps {
  number: number
  question: DraftQuizQuestion
  onChange: (question: DraftQuizQuestion) => void
  onRemove: () => void
}

function QuestionItem({ number, question, onChange, onRemove }: QuestionItemProps) {
  const radioName = useId()
  const name = (label: string) => `${number}번째 문제 ${label}`

  const setChoices = (choices: DraftQuizChoice[]) => onChange({ ...question, choices })
  const choose = (index: number) =>
    setChoices(
      question.choices.map((choice, position) => ({ ...choice, correct: position === index })),
    )

  return (
    <li className={styles.item}>
      <div className={styles.head}>
        <span className={styles.kind}>
          {number}번째 문제{' '}
          {question.questionId === null ? '(새 문제)' : `(${question.questionId})`}
        </span>
        <button type="button" onClick={onRemove} aria-label={name('삭제')}>
          삭제
        </button>
      </div>

      <label className={styles.field}>
        <span>
          {name('질문')} (최대 {PROMPT_MAX}자, 지금 {question.prompt.length}자)
        </span>
        <textarea
          rows={2}
          value={question.prompt}
          onChange={(event) => onChange({ ...question, prompt: event.target.value })}
        />
      </label>

      <fieldset className={styles.list}>
        <legend className={styles.hint}>보기 (정답 하나를 고릅니다)</legend>
        {question.choices.map((choice, index) => (
          <div key={index} className={styles.row}>
            <label className={styles.checkField}>
              <input
                type="radio"
                name={radioName}
                checked={choice.correct}
                onChange={() => choose(index)}
              />
              <span>{name(`${index + 1}번째 보기를 정답으로`)}</span>
            </label>
            <label className={styles.field}>
              <span>
                {name(`${index + 1}번째 보기`)}
                {choice.choiceId === null ? ' (새 보기)' : ` (${choice.choiceId})`} (최대{' '}
                {CHOICE_MAX}
                자, 지금 {choice.text.length}자)
              </span>
              <input
                type="text"
                value={choice.text}
                onChange={(event) =>
                  setChoices(
                    question.choices.map((item, position) =>
                      position === index ? { ...item, text: event.target.value } : item,
                    ),
                  )
                }
              />
            </label>
            <button
              type="button"
              onClick={() =>
                setChoices(question.choices.filter((_, position) => position !== index))
              }
              aria-label={name(`${index + 1}번째 보기 삭제`)}
            >
              삭제
            </button>
          </div>
        ))}
        <div className={styles.add}>
          <button
            type="button"
            onClick={() =>
              setChoices([...question.choices, { choiceId: null, text: '', correct: false }])
            }
            disabled={question.choices.length >= MAX_CHOICES}
          >
            {name('보기 추가')}
          </button>
        </div>
      </fieldset>

      <label className={styles.field}>
        <span>
          {name('풀이')} (최대 {EXPLANATION_MAX}자, 지금 {question.explanation.length}자)
        </span>
        <textarea
          rows={3}
          value={question.explanation}
          onChange={(event) => onChange({ ...question, explanation: event.target.value })}
        />
      </label>
    </li>
  )
}
