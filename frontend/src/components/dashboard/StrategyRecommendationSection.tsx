import { ArrowRight, ShieldAlert } from 'lucide-react'
import { motion } from 'motion/react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import type { StrategyRecommendation } from '@/types/dashboard'
import styles from './StrategyRecommendationSection.module.css'

interface StrategyRecommendationSectionProps {
  strategies: StrategyRecommendation[]
}

/** 세 가지 MVP 전략의 시장 적합 근거와 위험을 백테스트 동선에 연결한다. */
export function StrategyRecommendationSection({
  strategies,
}: StrategyRecommendationSectionProps) {
  return (
    <section className={styles.section} aria-labelledby="strategy-recommendation-title">
      <div className={styles.sectionHeader}>
        <div>
          <p>이 시장에 맞는 전략</p>
          <h2 id="strategy-recommendation-title">현재 시장에서 살펴볼 퀀트 전략</h2>
        </div>
        <span>적합도는 예시이며 예상 수익률이 아닙니다.</span>
      </div>

      <div className={styles.grid}>
        {strategies.map((strategy, index) => (
          <motion.article
            className={styles.card}
            key={strategy.id}
            initial={{ opacity: 0, y: 30, scale: 0.985 }}
            whileInView={{ opacity: 1, y: 0, scale: 1 }}
            viewport={{ once: false, amount: 0.2 }}
            transition={{
              delay: index * 0.09,
              duration: 0.58,
              ease: [0.22, 1, 0.36, 1],
            }}
          >
            <div className={styles.rank}>0{index + 1}</div>
            <div className={styles.cardHeader}>
              <h3>{strategy.name}</h3>
              <strong>{strategy.suitability}%</strong>
            </div>
            <div className={styles.meter} aria-label={`시장 적합도 ${strategy.suitability}%`}>
              <motion.span
                initial={{ width: 0 }}
                whileInView={{ width: `${strategy.suitability}%` }}
                viewport={{ once: false }}
                transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
              />
            </div>
            <p className={styles.reason}>{strategy.reason}</p>
            <p className={styles.risk}>
              <ShieldAlert size={14} aria-hidden="true" />
              <span>{strategy.risk}</span>
            </p>
            <div className={styles.action}>
              <Button asChild size="lg">
                <Link to={strategy.backtestPath}>
                  백테스트에서 확인하기
                  <ArrowRight aria-hidden="true" />
                </Link>
              </Button>
            </div>
          </motion.article>
        ))}
      </div>
    </section>
  )
}
