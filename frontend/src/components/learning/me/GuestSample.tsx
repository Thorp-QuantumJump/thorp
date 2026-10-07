import { useId } from 'react'
import { Link } from 'react-router-dom'
import { useLoginReturn } from '@/auth/useLoginReturn'
import { STAGE_LEAD_PATH, medalPath, type MedalKind } from './medalShapes'
import styles from './GuestSample.module.css'

interface GuestSampleProps {
  /**
   * 놓이는 자리. 배우기 목록의 요약 패널 자리(`list`), 글 끝(`end`), '나의 학습' 화면(`me`, 더 큰 칸)이다
   * (design-notes §3.5, 목업 `guestSample`).
   */
  place: 'list' | 'end' | 'me'
}

/**
 * 비로그인 사용자의 기록 자리(013 FR-020, design-notes §3.5).
 *
 * <p>'로그인하면 학습 기록이 남습니다.' 한 줄과 로그인 연결, 숫자·상태 모양·윤곽선·점선이 없는 견본 그림만 둔다.
 * 견본은 흐린 길 위의 도장 원판과 메달 실루엣이고 보조 기술에 읽히지 않는다(`aria-hidden`). 실선 회색 윤곽은
 * '아직 안 함'으로, 점선은 '준비 중'으로 읽히므로 견본에 쓰지 않는다.
 *
 * <p><b>글 끝에서는 로그인을 단추가 아니라 글자 연결로 둔다.</b> 글 화면의 행동 버튼은 '이 예시로 전략 진단하기'
 * 하나다(constitution II, plan Constitution Check II). 배우기 목록과 '나의 학습' 화면에는 다른 행동 버튼이 없어
 * 목업대로 초록 단추 모양이다.
 *
 * <p>로그인 연결은 014의 `useLoginReturn`으로 지금 화면 주소를 적어 두고 떠난다(research R34). 013이 따로 저장
 * 키를 만들지 않는다.
 */
export function GuestSample({ place }: GuestSampleProps) {
  const headingId = useId()
  const rememberReturn = useLoginReturn()

  return (
    <section
      className={place === 'me' ? `${styles.sample} ${styles.tall}` : styles.sample}
      aria-labelledby={headingId}
      data-guest-sample={place}
    >
      <div className={styles.row}>
        <h2 id={headingId} className={styles.title}>
          로그인하면 학습 기록이 남습니다.
        </h2>
        <Link className={place === 'end' ? styles.loginLink : styles.login} to="/login" onClick={rememberReturn}>
          로그인
        </Link>
      </div>
      <SampleArt />
    </section>
  )
}

/** 시작과 끝 높이가 같은 수평 길 위에 도장 원판 셋과 메달 실루엣을 놓은 견본(목업 `sampleArt`). */
function SampleArt() {
  const id = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  const road = 'M16 78 C66 52 116 52 166 78 S 266 104 316 78'
  const fill = 'rgb(3 199 90 / 0.18)'
  const shade = 'rgb(3 199 90 / 0.09)'
  const groove = 'rgb(0 0 0 / 0.28)'

  // 도장 견본: 윤곽선 없이 채운 원판과 안쪽의 눌린 홈. 실선 회색도 점선도 아니다.
  const stamp = (x: number, y: number, face = fill) => (
    <g key={`${x}-${y}`}>
      <ellipse cx={x} cy={y + 3.5} rx="12" ry="12" fill={shade} />
      <circle cx={x} cy={y} r="12" fill={face} />
      <circle cx={x} cy={y} r="8.6" fill="none" stroke={groove} strokeWidth="1.2" />
      <path d={`M${x - 6.5} ${y - 6} A8.5 8.5 0 0 1 ${x + 3} ${y - 8.4}`} fill="none" stroke="rgb(255 255 255 / 0.16)" strokeWidth="1.4" strokeLinecap="round" />
    </g>
  )
  // 메달 실루엣: 왼쪽 위에서 빛이 오는 초록 채움. 윤곽·숫자 없음.
  const silhouette = (kind: MedalKind, x: number, y: number, scale: number) => (
    <path
      key={kind}
      d={medalPath(kind)}
      fill={`url(#${id}-object)`}
      transform={`translate(${x} ${y}) scale(${scale}) translate(-50 -50)`}
    />
  )

  return (
    <div className={styles.art} aria-hidden="true">
      <svg viewBox="0 0 360 124" focusable="false">
        <defs>
          <linearGradient id={`${id}-road`} x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stopColor="#03C75A" stopOpacity="0.05" />
            <stop offset="0.3" stopColor="#03C75A" stopOpacity="0.18" />
            <stop offset="1" stopColor="#2FDC7A" stopOpacity="0.18" />
          </linearGradient>
          <linearGradient id={`${id}-object`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="rgb(126 240 176)" stopOpacity="0.36" />
            <stop offset="1" stopColor="rgb(3 199 90)" stopOpacity="0.1" />
          </linearGradient>
          <radialGradient id={`${id}-glow`}>
            <stop offset="0" stopColor="#2FDC7A" stopOpacity="0.9" />
            <stop offset="0.35" stopColor="#2FDC7A" stopOpacity="0.35" />
            <stop offset="1" stopColor="#2FDC7A" stopOpacity="0" />
          </radialGradient>
        </defs>
        <path d={road} fill="none" stroke="rgb(255 255 255 / 0.035)" strokeWidth="18" strokeLinecap="round" />
        <path d={road} fill="none" stroke={`url(#${id}-road)`} strokeWidth="5" strokeLinecap="round" />
        {stamp(66, 60.6)}
        {stamp(116, 60.6)}
        {stamp(166, 78, 'rgb(3 199 90 / 0.11)')}
        <path d="M216 95.4 V74 M266 95.4 V74" stroke="rgb(3 199 90 / 0.12)" strokeWidth="1.6" />
        {silhouette('read', 216, 50, 0.42)}
        {silhouette('first', 266, 50, 0.4)}
        {silhouette('all', 296, 22, 0.32)}
        {silhouette('terms', 240, 14, 0.32)}
        <circle cx="322" cy="56" r="26" fill={`url(#${id}-glow)`} opacity="0.35" />
        <ellipse cx="322" cy="80" rx="15" ry="2.6" fill="rgb(3 199 90 / 0.08)" />
        <g transform="translate(322 59.5) scale(0.42) translate(-50 -50)">
          <path d={medalPath('stage')} fill={`url(#${id}-object)`} />
          <g fill="none" stroke={groove} strokeWidth="3.2" strokeLinejoin="round">
            <path d={STAGE_LEAD_PATH} />
            <circle cx="50" cy="41" r="13" />
          </g>
        </g>
      </svg>
    </div>
  )
}
