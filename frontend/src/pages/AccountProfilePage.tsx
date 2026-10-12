import { BookOpenCheck, CircleCheck, UserRound } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { normalizeApiError } from '@/api/client'
import { useAuth } from '@/auth/auth-context'
import { AppHeader } from '@/components/navigation/AppHeader'
import { AuthField } from '@/components/auth/AuthField'
import { isFirstStageReleased } from '@/components/learning/curriculum/stages'
import { LearningResetSection } from '@/components/learning/me/LearningResetSection'
import { RecordLoadFailure } from '@/components/learning/me/RecordLoadFailure'
import heading from '@/components/common/PageHeading.module.css'
import { Button } from '@/components/ui/button'
import { useCurriculum } from '@/hooks/useCurriculum'
import styles from './AccountProfilePage.module.css'

const nicknameRuleMessage = '닉네임은 2~20자로 입력해 주세요.'

/**
 * 회원정보 수정 화면. 이메일은 보기만 하고 닉네임 하나를 바꾼다(`PATCH /accounts/me`).
 *
 * 저장에 성공하면 로그인 상태의 사용자가 바뀐 값으로 갈아 끼워져 머리 메뉴의 닉네임도 함께 바뀐다.
 * 아래 '학습 기록' 구역에서 배우기의 나의 학습 기록 전체를 지운다(013 FR-025). 나의 학습은 첫 단계를 공개할 때
 * 켜므로(FR-031) 이 구역도 커리큘럼으로 공개를 확인한 뒤에만 보인다(research R36). 확인하지 못하면 구역에 실패 한 줄과
 * '다시 시도'를 둔다. 개발용 미리보기(`preview`)는 서버를 부르지 않고 구역을 늘 보인다.
 */
export function AccountProfilePage({ preview = false }: { preview?: boolean }) {
  const { user, updateNickname } = useAuth()
  const [nickname, setNickname] = useState(user?.nickname ?? '')
  const [nicknameError, setNicknameError] = useState('')
  const [formError, setFormError] = useState('')
  const [notice, setNotice] = useState('')
  const [pending, setPending] = useState(false)
  const curriculum = useCurriculum({ enabled: !preview })
  // 나의 학습은 첫 단계를 공개할 때 켠다(013 FR-031). 공개를 확인하지 못하면 구역을 빼지 않고 실패 한 줄과 '다시 시도'를
  // 둔다. 학습 초기화로 가는 길(FR-025)이 말없이 사라지지 않게 하려는 것이다. 미리보기는 서버를 부르지 않고 구역을 보인다.
  const learning = preview
    ? 'on'
    : curriculum.state.status === 'failed'
      ? 'failed'
      : curriculum.state.status === 'loaded' && isFirstStageReleased(curriculum.state.curriculum)
        ? 'on'
        : 'off'

  // 저장 성공 안내는 지우지 않는다. 저장이 성공하면 바로 이 값이 바뀌어 여기가 다시 돈다.
  useEffect(() => {
    setNickname(user?.nickname ?? '')
    setNicknameError('')
  }, [user?.nickname])

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (pending) return

    const trimmed = nickname.trim()
    setNotice('')
    setFormError('')
    if (trimmed.length < 2 || trimmed.length > 20) {
      setNicknameError(nicknameRuleMessage)
      return
    }
    if (trimmed === user?.nickname) {
      setNicknameError('지금 쓰는 닉네임과 같습니다.')
      return
    }
    setNicknameError('')

    if (preview) {
      setNotice('미리보기 화면이라 저장하지 않았습니다.')
      return
    }

    setPending(true)
    try {
      await updateNickname(trimmed)
      setNotice('닉네임을 바꿨습니다.')
    } catch (rawError) {
      const error = normalizeApiError(rawError)
      const fieldError = error.fieldErrors.find((item) => item.field === 'nickname')
      if (fieldError) {
        setNicknameError(fieldError.message)
      } else if (error.is('INVALID_INPUT')) {
        setNicknameError(nicknameRuleMessage)
      } else {
        setFormError(error.message)
      }
    } finally {
      setPending(false)
    }
  }

  return (
    <div className={styles.page}>
      <AppHeader preview={preview} />
      <main className={styles.main}>
        {preview ? (
          <p className={styles.previewNotice} role="note">
            개발용 예시 화면입니다. 실제로 로그인한 상태가 아니며, 입력한 내용은 저장되지 않습니다.
          </p>
        ) : null}
        <div className={styles.heading}>
          <span className={`${styles.eyebrow} ${heading.classicOnly}`}>ACCOUNT / PROFILE</span>
          <p className={heading.eyebrow} aria-hidden="true">ACCOUNT</p>
          <h1 className={styles.title}>
            <em className={heading.emphasis}>회원정보</em> 수정
          </h1>
          <p className={styles.lead}>회원 정보를 확인하고 수정할 내용을 입력할 수 있습니다.</p>
        </div>

        <section className={styles.section} aria-labelledby="profile-section-title">
          <div className={styles.sectionHeading}>
            <span className={`${styles.sectionIndex} ${heading.classicOnly}`}>01</span>
            <span className={styles.sectionIcon} aria-hidden="true">
              <UserRound size={18} />
            </span>
            <div>
              <h2 id="profile-section-title">기본 정보</h2>
              <p>현재 로그인한 계정의 정보입니다.</p>
            </div>
          </div>

          <form className={styles.form} noValidate onSubmit={handleSubmit}>
            <AuthField
              label="이메일"
              type="email"
              name="email"
              value={user?.email ?? ''}
              hint="가입할 때 인증한 이메일입니다. 이 화면에서는 변경할 수 없습니다."
              readOnly
            />
            <AuthField
              label="닉네임"
              type="text"
              name="nickname"
              autoComplete="nickname"
              minLength={2}
              maxLength={20}
              value={nickname}
              hint="2~20자로 입력해 주세요."
              error={nicknameError}
              disabled={pending}
              onChange={(event) => {
                setNickname(event.target.value)
                setNicknameError('')
                setFormError('')
                setNotice('')
              }}
            />

            {notice ? (
              <p className={styles.notice} role="status">
                <CircleCheck size={18} aria-hidden="true" />
                {notice}
              </p>
            ) : null}
            {formError ? (
              <p className={styles.formError} role="alert">
                {formError}
              </p>
            ) : null}

            <div className={styles.actions}>
              <p>이메일은 바꿀 수 없고, 닉네임만 바꿀 수 있습니다.</p>
              <Button type="submit" className={styles.submit} disabled={pending}>
                {pending ? '저장하는 중…' : '닉네임 저장'}
              </Button>
            </div>
          </form>
        </section>

        {learning !== 'off' ? (
          <section className={styles.section} aria-labelledby="learning-section-title">
            <div className={styles.sectionHeading}>
              <span className={`${styles.sectionIndex} ${heading.classicOnly}`}>02</span>
              <span className={styles.sectionIcon} aria-hidden="true">
                <BookOpenCheck size={18} />
              </span>
              <div>
                <h2 id="learning-section-title">학습 기록</h2>
                <p>배우기에서 읽은 글과 확인 문제의 기록입니다.</p>
              </div>
            </div>
            <div className={styles.form}>
              {learning === 'on' ? (
                <LearningResetSection preview={preview} />
              ) : (
                <RecordLoadFailure onRetry={curriculum.retry} />
              )}
            </div>
          </section>
        ) : null}
      </main>
    </div>
  )
}
