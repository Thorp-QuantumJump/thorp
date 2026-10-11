import { ENAMEL, type Material } from './materials'
import styles from './materials.module.css'

/** 재질 그라디언트 정의 하나. 식별자는 `{앞머리}-{재질}`이다(`materials.ts`의 `paint`가 가리킨다). */
function definition(prefix: string, material: Material) {
  const id = `${prefix}-${material}`
  switch (material) {
    case 'gmetal':
      return (
        <linearGradient key={id} id={id} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#C8FFE0" />
          <stop offset="0.18" stopColor="#7EF0B0" />
          <stop className={styles.brandSoft} offset="0.4" />
          <stop className={styles.brand} offset="0.62" />
          <stop offset="0.82" stopColor="#0B5E33" />
          <stop offset="1" stopColor="#7EF0B0" />
        </linearGradient>
      )
    case 'gtext':
      return (
        <linearGradient key={id} id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#EFFFF5" />
          <stop offset="0.38" stopColor="#9DF5C2" />
          <stop className={styles.brandSoft} offset="0.7" />
          <stop offset="1" stopColor="#0B8F45" />
        </linearGradient>
      )
    case 'face':
      return (
        <radialGradient key={id} id={id} cx="0.35" cy="0.25" r="0.9">
          <stop offset="0" stopColor="#173826" />
          <stop offset="0.65" stopColor="#0F1A14" />
          <stop offset="1" stopColor="#0B140F" />
        </radialGradient>
      )
    case 'metal':
    case 'metal2':
      return (
        <linearGradient key={id} id={id} x1="0" y1="0" x2="1" y2="1">
          {material === 'metal' ? <stop offset="0" stopColor="#F3E7C0" /> : null}
          <stop offset={material === 'metal' ? '0.04' : '0'} stopColor="#EEF4F0" />
          <stop offset="0.3" stopColor="#B4C2BA" />
          <stop offset="0.55" stopColor="#7C8A83" />
          <stop offset="0.75" stopColor="#DDE6E1" />
          <stop offset="1" stopColor="#5F6C66" />
        </linearGradient>
      )
    case 'glass':
      return (
        <radialGradient key={id} id={id} cx="0.3" cy="0.22" r="0.75">
          <stop offset="0" stopColor="#fff" stopOpacity="0.42" />
          <stop offset="0.55" stopColor="#fff" stopOpacity="0.06" />
          <stop offset="1" stopColor="#000" stopOpacity="0.18" />
        </radialGradient>
      )
    case 'pin':
      return (
        <linearGradient key={id} id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#fff" stopOpacity="0.38" />
          <stop offset="0.42" stopColor="#fff" stopOpacity="0" />
          <stop offset="0.62" stopColor="#000" stopOpacity="0" />
          <stop offset="1" stopColor="#000" stopOpacity="0.5" />
        </linearGradient>
      )
    case 'hl':
      return (
        <linearGradient key={id} id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#fff" stopOpacity="0.26" />
          <stop offset="1" stopColor="#fff" stopOpacity="0.1" />
        </linearGradient>
      )
    case 'sweep':
      return (
        <linearGradient key={id} id={id} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#fff" stopOpacity="0" />
          <stop offset="0.5" stopColor="#fff" stopOpacity="0.62" />
          <stop offset="1" stopColor="#fff" stopOpacity="0" />
        </linearGradient>
      )
    case 'shadow':
      return (
        <radialGradient key={id} id={id} cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stopColor="#000" stopOpacity="0.55" />
          <stop offset="1" stopColor="#000" stopOpacity="0" />
        </radialGradient>
      )
    case 'glow':
      return (
        <radialGradient key={id} id={id}>
          <stop className={styles.brandSoft} offset="0" stopOpacity="0.9" />
          <stop className={styles.brandSoft} offset="0.35" stopOpacity="0.35" />
          <stop className={styles.brandSoft} offset="1" stopOpacity="0" />
        </radialGradient>
      )
    case 'en-special':
      // 특별 갈래의 옅은 홀로그램 박
      return (
        <linearGradient key={id} id={id} x1="0" y1="0" x2="1" y2="1">
          <stop className={styles.brandSoft} offset="0" />
          <stop offset="0.35" stopColor="#7FE0C4" />
          <stop offset="0.6" stopColor="#E9E3B4" />
          <stop offset="0.8" stopColor="#C7F0D8" />
          <stop className={styles.brandSoft} offset="1" />
        </linearGradient>
      )
    default: {
      const [light, middle, dark] = ENAMEL[material.slice(3) as keyof typeof ENAMEL]
      return (
        <radialGradient key={id} id={id} cx="0.35" cy="0.25" r="0.9">
          <stop offset="0" stopColor={light} />
          <stop offset="0.45" stopColor={middle} />
          <stop offset="1" stopColor={dark} />
        </radialGradient>
      )
    }
  }
}

/** 그림이 쓰는 재질만 `<defs>`에 둔다. */
export function MaterialDefs({
  prefix,
  materials,
}: {
  prefix: string
  materials: readonly Material[]
}) {
  return <>{materials.map((material) => definition(prefix, material))}</>
}
