import { ArrowLeft, ArrowRight, KeyRound, MailCheck } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  confirmEmailVerification,
  normalizeEmail,
  requestEmailVerification,
  signup,
} from '@/api/auth'
import { ApiClientError, normalizeApiError } from '@/api/client'
import { AuthField } from '@/components/auth/AuthField'
import { AuthStatus } from '@/components/auth/AuthStatus'
import formStyles from '@/components/auth/AuthForm.module.css'
import type { AuthFieldName, RequestStatus } from '@/types/auth'
import styles from './SignupPage.module.css'

type FieldErrors = Partial<Record<AuthFieldName | 'passwordConfirm', string>>
type ActionName = 'send' | 'confirm' | 'signup'
type StatusMessage = { message: string; tone: 'info' | 'success' | 'error' }
type SignupStep = 1 | 2

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const phonePattern = /^01[016789]\d{7,8}$/
const authFieldNames: AuthFieldName[] = [
  'email',
  'verificationCode',
  'password',
  'nickname',
  'phoneNumber',
]

function emailError(email: string): string | undefined {
  const normalized = normalizeEmail(email)
  if (!normalized) return '이메일을 입력해 주세요.'
  if (!emailPattern.test(normalized)) return '올바른 이메일 형식으로 입력해 주세요.'
  return undefined
}

function fieldFromMessage(message: string): AuthFieldName | undefined {
  const field = message.match(/^(email|verificationCode|password|nickname|phoneNumber)\s*:/)?.[1]
  return authFieldNames.find((fieldName) => fieldName === field)
}

function fieldFromApiError(error: ApiClientError, fallback: AuthFieldName): AuthFieldName {
  const mapped: Partial<Record<NonNullable<ApiClientError['code']>, AuthFieldName>> = {
    DUPLICATE_EMAIL: 'email',
    DUPLICATE_PHONE: 'phoneNumber',
    EMAIL_VERIFICATION_REQUIRED: 'verificationCode',
    EMAIL_VERIFICATION_NOT_FOUND: 'verificationCode',
    EMAIL_VERIFICATION_INVALID: 'verificationCode',
    EMAIL_VERIFICATION_EXPIRED: 'verificationCode',
  }

  return (error.code && mapped[error.code]) || fieldFromMessage(error.message) || fallback
}

/** 이메일 소유 확인부터 계정 생성까지 한 흐름으로 제공한다. */
export function SignupPage() {
  const navigate = useNavigate()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const panelHeadingRef = useRef<HTMLHeadingElement>(null)
  const emailRef = useRef<HTMLInputElement>(null)
  const verificationCodeRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const passwordConfirmRef = useRef<HTMLInputElement>(null)
  const nicknameRef = useRef<HTMLInputElement>(null)
  const phoneNumberRef = useRef<HTMLInputElement>(null)
  const mountedRef = useRef(true)
  const pendingRef = useRef<Record<ActionName, boolean>>({
    send: false,
    confirm: false,
    signup: false,
  })
  const requestIdRef = useRef<Record<ActionName, number>>({ send: 0, confirm: 0, signup: 0 })
  const controllerRef = useRef<Partial<Record<ActionName, AbortController>>>({})

  const [email, setEmail] = useState('')
  const [verificationCode, setVerificationCode] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [nickname, setNickname] = useState('')
  const [phoneNumber, setPhoneNumber] = useState('')
  const [step, setStep] = useState<SignupStep>(1)
  const [isPreviewMode, setIsPreviewMode] = useState(false)
  const [requestedEmail, setRequestedEmail] = useState<string | null>(null)
  const [verifiedEmail, setVerifiedEmail] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [status, setStatus] = useState<StatusMessage | null>(null)
  const [sendStatus, setSendStatus] = useState<RequestStatus>('idle')
  const [confirmStatus, setConfirmStatus] = useState<RequestStatus>('idle')
  const [signupStatus, setSignupStatus] = useState<RequestStatus>('idle')

  useEffect(() => {
    const controllers = controllerRef.current
    mountedRef.current = true

    headingRef.current?.focus()

    return () => {
      mountedRef.current = false
      Object.values(controllers).forEach((controller) => controller?.abort())
    }
  }, [])

  const focusField = (field: AuthFieldName | 'passwordConfirm') => {
    const refs = {
      email: emailRef,
      verificationCode: verificationCodeRef,
      password: passwordRef,
      passwordConfirm: passwordConfirmRef,
      nickname: nicknameRef,
      phoneNumber: phoneNumberRef,
    }
    refs[field].current?.focus()
  }

  const beginRequest = (action: ActionName) => {
    if (pendingRef.current[action]) return null

    pendingRef.current[action] = true
    const id = requestIdRef.current[action] + 1
    requestIdRef.current[action] = id
    controllerRef.current[action]?.abort()
    const controller = new AbortController()
    controllerRef.current[action] = controller
    return { id, controller }
  }

  const isCurrentRequest = (action: ActionName, id: number) =>
    mountedRef.current && requestIdRef.current[action] === id

  const finishRequest = (action: ActionName, id: number) => {
    if (requestIdRef.current[action] !== id) return
    pendingRef.current[action] = false
    delete controllerRef.current[action]
  }

  const showRequestError = (
    rawError: unknown,
    fallbackField: AuthFieldName,
    action: ActionName,
  ) => {
    const error = normalizeApiError(rawError)
    const nextErrors: FieldErrors = {}

    error.fieldErrors.forEach((fieldError) => {
      nextErrors[fieldError.field] = fieldError.message
    })
    const focusTarget = Object.keys(nextErrors)[0] as AuthFieldName | undefined
    const fallbackTarget = fieldFromApiError(error, fallbackField)
    if (!focusTarget) nextErrors[fallbackTarget] = error.message

    setFieldErrors((current) => ({ ...current, ...nextErrors }))
    setStatus({ message: error.message, tone: 'error' })
    if (action === 'send') setSendStatus('error')
    if (action === 'confirm') setConfirmStatus('error')
    if (action === 'signup') setSignupStatus('error')
    window.requestAnimationFrame(() => {
      focusField(focusTarget ?? fallbackTarget)
    })
  }

  const handleEmailChange = (value: string) => {
    const previousCanonicalEmail = normalizeEmail(email)
    const nextCanonicalEmail = normalizeEmail(value)
    setEmail(value)
    setFieldErrors((current) => ({ ...current, email: undefined }))

    if (nextCanonicalEmail !== previousCanonicalEmail) {
      setRequestedEmail(null)
      setVerifiedEmail(null)
      setVerificationCode('')
      setConfirmStatus('idle')
    }
  }

  const handleSendVerification = async () => {
    const validationError = emailError(email)
    if (validationError) {
      setFieldErrors((current) => ({ ...current, email: validationError }))
      setStatus({ message: validationError, tone: 'error' })
      emailRef.current?.focus()
      return
    }

    const request = beginRequest('send')
    if (!request) return

    const canonicalEmail = normalizeEmail(email)
    setFieldErrors((current) => ({ ...current, email: undefined, verificationCode: undefined }))
    setSendStatus('pending')
    setStatus({ message: '인증번호 발송을 요청하고 있습니다.', tone: 'info' })

    if (isPreviewMode) {
      setRequestedEmail(canonicalEmail)
      setVerifiedEmail(null)
      setVerificationCode('')
      setSendStatus('success')
      setConfirmStatus('idle')
      setStatus({
        message: 'UI 미리보기용 인증번호 입력칸을 열었습니다. 임의의 6자리를 입력하세요.',
        tone: 'info',
      })
      finishRequest('send', request.id)
      window.requestAnimationFrame(() => verificationCodeRef.current?.focus())
      return
    }

    try {
      await requestEmailVerification({ email: canonicalEmail }, request.controller.signal)
      if (!isCurrentRequest('send', request.id) || normalizeEmail(email) !== canonicalEmail) return

      setRequestedEmail(canonicalEmail)
      setVerifiedEmail(null)
      setVerificationCode('')
      setSendStatus('success')
      setConfirmStatus('idle')
      setStatus({
        message: '인증번호를 보냈습니다. 3분 안에 6자리 번호를 입력해 주세요.',
        tone: 'success',
      })
      window.requestAnimationFrame(() => verificationCodeRef.current?.focus())
    } catch (error) {
      if (isCurrentRequest('send', request.id)) showRequestError(error, 'email', 'send')
    } finally {
      finishRequest('send', request.id)
    }
  }

  const handleConfirmVerification = async () => {
    const canonicalEmail = normalizeEmail(email)
    if (requestedEmail !== canonicalEmail) {
      const message = '현재 이메일로 인증번호를 먼저 요청해 주세요.'
      setFieldErrors((current) => ({ ...current, email: message }))
      setStatus({ message, tone: 'error' })
      emailRef.current?.focus()
      return
    }
    if (!/^\d{6}$/.test(verificationCode)) {
      const message = '인증번호 6자리를 입력해 주세요.'
      setFieldErrors((current) => ({ ...current, verificationCode: message }))
      setStatus({ message, tone: 'error' })
      verificationCodeRef.current?.focus()
      return
    }

    const request = beginRequest('confirm')
    if (!request) return

    setFieldErrors((current) => ({ ...current, verificationCode: undefined }))
    setConfirmStatus('pending')
    setStatus({ message: '인증번호를 확인하고 있습니다.', tone: 'info' })

    if (isPreviewMode) {
      setVerifiedEmail(canonicalEmail)
      setVerificationCode('')
      setConfirmStatus('success')
      setStatus({ message: 'UI 미리보기에서 이메일 확인을 완료했습니다.', tone: 'success' })
      finishRequest('confirm', request.id)
      window.requestAnimationFrame(() => passwordRef.current?.focus())
      return
    }

    try {
      await confirmEmailVerification(
        { email: canonicalEmail, verificationCode },
        request.controller.signal,
      )
      if (!isCurrentRequest('confirm', request.id) || normalizeEmail(email) !== canonicalEmail) return

      setVerifiedEmail(canonicalEmail)
      setVerificationCode('')
      setConfirmStatus('success')
      setStatus({ message: '이메일 확인이 완료되었습니다.', tone: 'success' })
      window.requestAnimationFrame(() => passwordRef.current?.focus())
    } catch (error) {
      if (!isCurrentRequest('confirm', request.id)) return
      const normalizedError = normalizeApiError(error)
      if (normalizedError.code === 'EMAIL_VERIFICATION_EXPIRED') {
        setRequestedEmail(null)
        setVerificationCode('')
      }
      showRequestError(normalizedError, 'verificationCode', 'confirm')
    } finally {
      finishRequest('confirm', request.id)
    }
  }

  const validateAccountStep = (): FieldErrors => {
    const nextErrors: FieldErrors = {}
    const currentEmailError = emailError(email)
    const canonicalEmail = normalizeEmail(email)

    if (currentEmailError) nextErrors.email = currentEmailError
    if (verifiedEmail !== canonicalEmail) nextErrors.verificationCode = '이메일 인증을 완료해 주세요.'
    if (password.length < 8 || password.length > 25) {
      nextErrors.password = '비밀번호는 8~25자로 입력해 주세요.'
    }
    if (password !== passwordConfirm) nextErrors.passwordConfirm = '비밀번호가 일치하지 않습니다.'

    return nextErrors
  }

  const validateProfileStep = (): FieldErrors => {
    const nextErrors: FieldErrors = {}

    if (nickname.trim().length < 2 || nickname.trim().length > 20) {
      nextErrors.nickname = '닉네임은 2~20자로 입력해 주세요.'
    }
    if (!phonePattern.test(phoneNumber)) {
      nextErrors.phoneNumber = '하이픈 없이 올바른 국내 휴대폰 번호를 입력해 주세요.'
    }

    return nextErrors
  }

  const handleContinue = () => {
    const nextErrors = validateAccountStep()
    const firstError = Object.keys(nextErrors)[0] as keyof FieldErrors | undefined

    if (firstError) {
      setFieldErrors(nextErrors)
      setStatus({ message: '계정 정보를 다시 확인해 주세요.', tone: 'error' })
      focusField(firstError)
      return
    }

    setFieldErrors({})
    setStatus(null)
    setStep(2)
    window.requestAnimationFrame(() => panelHeadingRef.current?.focus())
  }

  const handleBackToAccount = () => {
    setFieldErrors({})
    setStatus(null)
    setStep(1)
    window.requestAnimationFrame(() => panelHeadingRef.current?.focus())
  }

  const handlePreviewModeChange = (enabled: boolean) => {
    setIsPreviewMode(enabled)
    setRequestedEmail(null)
    setVerifiedEmail(null)
    setVerificationCode('')
    setSendStatus('idle')
    setConfirmStatus('idle')
    setFieldErrors({})
    setStatus(
      enabled
        ? { message: '개발용 UI 미리보기입니다. 실제 계정은 생성되지 않습니다.', tone: 'info' }
        : null,
    )
  }

  const handleSignup = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (step === 1) {
      handleContinue()
      return
    }

    const accountErrors = validateAccountStep()
    if (Object.keys(accountErrors).length > 0) {
      setFieldErrors(accountErrors)
      setStatus({ message: '계정 정보를 다시 확인해 주세요.', tone: 'error' })
      setStep(1)
      window.requestAnimationFrame(() => {
        const firstError = Object.keys(accountErrors)[0] as keyof FieldErrors
        focusField(firstError)
      })
      return
    }

    const nextErrors = validateProfileStep()
    const firstError = Object.keys(nextErrors)[0] as keyof FieldErrors | undefined

    if (firstError) {
      setFieldErrors(nextErrors)
      setStatus({ message: '입력한 내용을 다시 확인해 주세요.', tone: 'error' })
      focusField(firstError)
      return
    }

    const request = beginRequest('signup')
    if (!request) return

    const canonicalEmail = normalizeEmail(email)
    setFieldErrors({})
    setSignupStatus('pending')
    setStatus({ message: '계정을 만들고 있습니다.', tone: 'info' })

    if (isPreviewMode) {
      setSignupStatus('success')
      setPassword('')
      setPasswordConfirm('')
      setVerificationCode('')
      finishRequest('signup', request.id)
      navigate('/login', {
        replace: true,
        state: { signupEmail: canonicalEmail },
      })
      return
    }

    try {
      const result = await signup(
        {
          email: canonicalEmail,
          password,
          nickname: nickname.trim(),
          phoneNumber,
        },
        request.controller.signal,
      )
      if (!isCurrentRequest('signup', request.id)) return

      setSignupStatus('success')
      setPassword('')
      setPasswordConfirm('')
      setVerificationCode('')
      navigate('/login', {
        replace: true,
        state: { signupEmail: normalizeEmail(result.email) },
      })
    } catch (error) {
      if (isCurrentRequest('signup', request.id)) showRequestError(error, 'email', 'signup')
    } finally {
      finishRequest('signup', request.id)
    }
  }

  const canonicalEmail = normalizeEmail(email)
  const canConfirm = requestedEmail === canonicalEmail && sendStatus === 'success'
  const isVerified = verifiedEmail === canonicalEmail && confirmStatus === 'success'
  const isAnyPending =
    sendStatus === 'pending' || confirmStatus === 'pending' || signupStatus === 'pending'

  return (
    <main className={styles.page}>
      <div className={styles.ambient} aria-hidden="true" />
      <header className={styles.header}>
        <Link className={styles.wordmark} to="/" aria-label="Thorp 처음 화면">
          Thorp<span>.</span>
        </Link>
        <Link className={styles.loginLink} to="/login">
          로그인
          <ArrowRight size={15} aria-hidden="true" />
        </Link>
      </header>

      <section className={styles.layout} aria-labelledby="signup-title">
        <aside className={styles.intro}>
          <span className={styles.eyebrow}>CREATE YOUR ACCOUNT</span>
          <h1 id="signup-title" ref={headingRef} tabIndex={-1}>
            시장을 확인하고,
            <br />
            전략을 기록하세요.
          </h1>
          <p>
            이메일을 확인한 뒤 계정을 만들 수 있습니다. 전략 진단과 시장 흐름은 로그인 없이도
            이용할 수 있습니다.
          </p>
          <Link className={styles.backLink} to="/">
            <ArrowLeft size={15} aria-hidden="true" />
            처음 화면으로
          </Link>
        </aside>

        <div className={styles.formPanel}>
          <div className={styles.panelHeading}>
            <span>{String(step).padStart(2, '0')}</span>
            <div>
              <h2 ref={panelHeadingRef} tabIndex={-1}>
                {step === 1 ? '계정 정보' : '프로필 정보'}
              </h2>
              <p>
                {step === 1
                  ? '이메일을 인증하고 비밀번호를 설정합니다.'
                  : '서비스에서 사용할 기본 정보를 입력합니다.'}
              </p>
            </div>
            <div className={styles.stepProgress} aria-label={`회원가입 ${step}단계, 총 2단계`}>
              <span data-active={step === 1} />
              <span data-active={step === 2} />
            </div>
          </div>

          <form className={styles.form} noValidate onSubmit={handleSignup}>
            {import.meta.env.DEV ? (
              <label className={styles.previewToggle}>
                <input
                  type="checkbox"
                  checked={isPreviewMode}
                  disabled={isAnyPending}
                  onChange={(event) => handlePreviewModeChange(event.target.checked)}
                />
                <span>
                  <strong>서버 없이 UI 흐름 미리보기</strong>
                  <small>실제 이메일 발송이나 계정 생성은 이루어지지 않습니다.</small>
                </span>
              </label>
            ) : null}

            {step === 1 ? (
              <>
                <div className={styles.verificationSection}>
                  <AuthField
                    ref={emailRef}
                    label="이메일"
                    type="email"
                    name="email"
                    autoComplete="email"
                    inputMode="email"
                    placeholder="name@example.com"
                    value={email}
                    error={fieldErrors.email}
                    disabled={isAnyPending}
                    onChange={(event) => handleEmailChange(event.target.value)}
                  />
                  <button
                    type="button"
                    className={formStyles.secondaryButton}
                    disabled={isAnyPending}
                    onClick={handleSendVerification}
                  >
                    {sendStatus === 'pending'
                      ? '발송 중…'
                      : requestedEmail === canonicalEmail
                        ? '다시 받기'
                        : '인증번호 받기'}
                  </button>
                </div>

                {canConfirm && !isVerified ? (
                  <div className={styles.verificationSection}>
                    <AuthField
                      ref={verificationCodeRef}
                      label="인증번호"
                      type="text"
                      name="verificationCode"
                      autoComplete="one-time-code"
                      inputMode="numeric"
                      maxLength={6}
                      placeholder="6자리 숫자"
                      value={verificationCode}
                      error={fieldErrors.verificationCode}
                      disabled={isAnyPending}
                      onChange={(event) => {
                        setVerificationCode(event.target.value.replace(/\D/g, '').slice(0, 6))
                        setFieldErrors((current) => ({
                          ...current,
                          verificationCode: undefined,
                        }))
                      }}
                    />
                    <button
                      type="button"
                      className={formStyles.secondaryButton}
                      disabled={isAnyPending}
                      onClick={handleConfirmVerification}
                    >
                      {confirmStatus === 'pending' ? '확인 중…' : '인증 확인'}
                    </button>
                  </div>
                ) : null}

                {isVerified ? (
                  <div className={styles.verifiedLabel}>
                    <MailCheck size={17} aria-hidden="true" />
                    {canonicalEmail} 인증 완료
                  </div>
                ) : null}

                <div className={styles.fieldGrid}>
                  <AuthField
                    ref={passwordRef}
                    label="비밀번호"
                    type="password"
                    name="password"
                    autoComplete="new-password"
                    minLength={8}
                    maxLength={25}
                    placeholder="8~25자"
                    value={password}
                    hint="다른 화면이나 브라우저 저장소에 보관하지 않습니다."
                    error={fieldErrors.password}
                    disabled={isAnyPending}
                    onChange={(event) => {
                      setPassword(event.target.value)
                      setFieldErrors((current) => ({ ...current, password: undefined }))
                    }}
                  />
                  <AuthField
                    ref={passwordConfirmRef}
                    label="비밀번호 확인"
                    type="password"
                    name="passwordConfirm"
                    autoComplete="new-password"
                    placeholder="비밀번호 다시 입력"
                    value={passwordConfirm}
                    error={fieldErrors.passwordConfirm}
                    disabled={isAnyPending}
                    onChange={(event) => {
                      setPasswordConfirm(event.target.value)
                      setFieldErrors((current) => ({
                        ...current,
                        passwordConfirm: undefined,
                      }))
                    }}
                  />
                </div>
              </>
            ) : (
              <>
                <div className={styles.stepSummary}>
                  <MailCheck size={17} aria-hidden="true" />
                  <span>
                    인증된 이메일
                    <strong>{canonicalEmail}</strong>
                  </span>
                </div>

                <div className={styles.fieldGrid}>
                  <AuthField
                    ref={nicknameRef}
                    label="닉네임"
                    type="text"
                    name="nickname"
                    autoComplete="nickname"
                    minLength={2}
                    maxLength={20}
                    placeholder="2~20자"
                    value={nickname}
                    hint="서비스 화면에 표시할 이름을 입력해 주세요."
                    error={fieldErrors.nickname}
                    disabled={isAnyPending}
                    onChange={(event) => {
                      setNickname(event.target.value)
                      setFieldErrors((current) => ({ ...current, nickname: undefined }))
                    }}
                  />
                  <AuthField
                    ref={phoneNumberRef}
                    label="휴대폰 번호"
                    type="tel"
                    name="phoneNumber"
                    autoComplete="tel-national"
                    inputMode="numeric"
                    maxLength={11}
                    placeholder="01012345678"
                    value={phoneNumber}
                    hint="하이픈 없이 숫자만 입력해 주세요."
                    error={fieldErrors.phoneNumber}
                    disabled={isAnyPending}
                    onChange={(event) => {
                      setPhoneNumber(event.target.value.replace(/\D/g, '').slice(0, 11))
                      setFieldErrors((current) => ({ ...current, phoneNumber: undefined }))
                    }}
                  />
                </div>
              </>
            )}

            {status ? <AuthStatus message={status.message} tone={status.tone} /> : null}

            {step === 1 ? (
              <button type="submit" className={formStyles.submitButton} disabled={isAnyPending}>
                다음 단계
                <ArrowRight size={17} aria-hidden="true" />
              </button>
            ) : (
              <div className={styles.formActions}>
                <button
                  type="button"
                  className={formStyles.secondaryButton}
                  disabled={isAnyPending}
                  onClick={handleBackToAccount}
                >
                  <ArrowLeft size={17} aria-hidden="true" />
                  이전 단계
                </button>
                <button
                  type="submit"
                  className={formStyles.submitButton}
                  disabled={isAnyPending}
                >
                  <KeyRound size={17} aria-hidden="true" />
                  {signupStatus === 'pending' ? '계정 생성 중…' : '계정 만들기'}
                </button>
              </div>
            )}
          </form>

          <p className={styles.loginPrompt}>
            이미 계정이 있나요? <Link to="/login">로그인</Link>
          </p>
        </div>
      </section>
    </main>
  )
}
