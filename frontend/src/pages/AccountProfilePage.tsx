import { CircleCheck, UserRound } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { useAuth } from '@/auth/auth-context'
import { AppHeader } from '@/components/navigation/AppHeader'
import { AuthField } from '@/components/auth/AuthField'
import heading from '@/components/common/PageHeading.module.css'
import { Button } from '@/components/ui/button'
import styles from './AccountProfilePage.module.css'

/** 현재 사용자 응답으로 확인할 수 있는 정보만 보여 주는 회원정보 수정 준비 화면이다. */
export function AccountProfilePage({ preview = false }: { preview?: boolean }) {
  const { user } = useAuth()
  const [nickname, setNickname] = useState(user?.nickname ?? '')
  const [nicknameError, setNicknameError] = useState('')
  const [notice, setNotice] = useState('')

  useEffect(() => {
    setNickname(user?.nickname ?? '')
    setNicknameError('')
    setNotice('')
  }, [user?.nickname])

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const trimmed = nickname.trim()
    if (trimmed.length < 2 || trimmed.length > 20) {
      setNicknameError('닉네임은 2~20자로 입력해 주세요.')
      setNotice('')
      return
    }
    setNicknameError('')
    setNotice('입력값을 확인했습니다. 서버 저장 기능은 아직 연결되지 않아 변경 내용은 저장되지 않았습니다.')
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
              hint="2~20자로 입력해 주세요. 중복 확인은 서버 연결 후 가능합니다."
              error={nicknameError}
              onChange={(event) => {
                setNickname(event.target.value)
                setNicknameError('')
                setNotice('')
              }}
            />

            {notice ? (
              <p className={styles.notice} role="status">
                <CircleCheck size={18} aria-hidden="true" />
                {notice}
              </p>
            ) : null}

            <div className={styles.actions}>
              <p>서버 기능이 연결되기 전에는 입력한 내용이 계정에 반영되지 않습니다.</p>
              <Button type="submit" className={styles.submit}>입력값 확인</Button>
            </div>
          </form>
        </section>
      </main>
    </div>
  )
}
