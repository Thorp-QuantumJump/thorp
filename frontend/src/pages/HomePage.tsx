import { ArrowUpRight } from 'lucide-react'
import { motion } from 'motion/react'
import { Link } from 'react-router-dom'
import styles from './HomePage.module.css'

/** 서비스 소개와 백테스트 화면 진입 동선을 제공한다. */
export function HomePage() {
  return (
    <main className={styles.page}>
      <div className={styles.glow} aria-hidden="true" />
      <motion.section
        className={styles.hero}
        initial="hidden"
        animate="visible"
        variants={{
          hidden: {},
          visible: { transition: { staggerChildren: 0.09 } },
        }}
      >
        <motion.p
          className={styles.eyebrow}
          variants={{ hidden: { opacity: 0 }, visible: { opacity: 1 } }}
        >
          QUANT RESEARCH PLATFORM
        </motion.p>
        <motion.h1
          variants={{
            hidden: { opacity: 0, y: 18 },
            visible: { opacity: 1, y: 0 },
          }}
          transition={{ duration: 0.55, ease: [0.22, 1, 0.36, 1] }}
        >
          Thorp<span>.</span>
        </motion.h1>
        <motion.p
          className={styles.description}
          variants={{ hidden: { opacity: 0 }, visible: { opacity: 1 } }}
        >
          숫자로 전략을 검증하고, 더 나은 투자 판단을 만듭니다.
        </motion.p>
        <motion.div
          variants={{
            hidden: { opacity: 0, y: 10 },
            visible: { opacity: 1, y: 0 },
          }}
        >
          <Link className={styles.cta} to="/dashboard">
            시작하기
            <ArrowUpRight aria-hidden="true" size={18} strokeWidth={1.8} />
          </Link>
        </motion.div>
      </motion.section>
      <p className={styles.version}>PROTOTYPE · 01</p>
    </main>
  )
}
