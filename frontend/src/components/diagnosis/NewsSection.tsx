import { Newspaper } from 'lucide-react'
import { useContext, useEffect, useId, useMemo, useRef, useState } from 'react'
import { fetchNewsSectors } from '@/api/marketNews'
import { InlineHelp } from '@/components/common/InlineHelp'
import { arrangeNews, NEWS_LIMITS, type ArrangedPart, type PartInput } from '@/diagnosis/newsArrangement'
import { groupNewsSectors } from '@/diagnosis/newsSectorGroups'
import { SectorSelectionContext } from '@/diagnosis/sector-selection-context'
import { newsRequestKey, useNewsDigestBatch, type NewsRequest, type NewsSettled } from '@/hooks/useNewsDigestBatch'
import { useNearViewport } from '@/hooks/useNearViewport'
import type { NewsArticle, NewsDigest, NewsDigestState, NewsSector } from '@/types/marketNews'
import styles from './NewsSection.module.css'

/*
  투자 대상 고르기 하단의 뉴스 구역(012 FR-001, 002 FR-019).

  - 세 부분이 이 순서로 선다: 주요 뉴스(1위 묶음을 크게 + 다음 넷), 고른 섹터 뉴스(섹터마다 둘), 분류별 뉴스(분류마다 셋).
    한 묶음은 먼저 나오는 부분에 한 번만 보이고, 모자라면 채우지 않는다(`arrangeNews`).
  - 분류의 섹터들은 한 번의 조회로 받는다. 섹터마다 받아 화면이 합치면 012의 주요도 규칙을 화면이 한 벌 더 갖게 된다.
  - 칸마다 보일 수보다 넉넉히 받는다. 앞 부분에 이미 보인 묶음을 건너뛰고도 칸을 채워야 하기 때문이다(아래 받을 수).
  - 섹터 칩으로 섹터 하나만 좁혀 보면 세 부분 대신 그 섹터의 묶음 목록이 선다(012 FR-005).
  - 012가 정한 상태(READY·EMPTY·STALE·PREPARING)를 다시 판정하지 않고 옮겨 적는다. 뉴스가 하나도 없는 상태(EMPTY·PREPARING)
    에서는 012의 문장만 두고 빈 칸들을 늘어놓지 않는다 — 준비 중인데 칸마다 '없다'고 말하면 사실과 다르다.
  - 뉴스는 투자 대상 선택을 바꾸지 않는다. 고른 투자 대상이 '고른 섹터 뉴스'를 정할 뿐이다(002 FR-019).
  - 화면 아래쪽 구역이라 가까이 왔을 때 조회를 시작한다.
*/

/**
 * 고른 섹터 칸과 섹터 하나로 좁혀 보기가 받는 수. 고른 섹터 칸은 주요 뉴스 다섯을 건너뛰고 둘을 채워야 한다(5 + 2에 여유를 더했다).
 * 좁혀 보기는 이 목록을 그대로 보인다.
 */
const SECTOR_FETCH_LIMIT = 10

/**
 * 분류 칸이 받는 수. 012 서버 상한(`thorp.news.max-digests`)과 같다. 분류 칸은 주요 뉴스 다섯과 그 분류에서 고른 섹터마다 둘을
 * 건너뛴 뒤 셋을 채워야 한다. 지금 한 분류에 투자 대상 섹터가 가장 많은 곳이 셋이라 5 + 2 × 3 + 3 = 14개면 되고, 남는 몫은
 * 여러 섹터에 걸친 묶음에 둔다. 고른 것에 따라 받을 수를 바꾸지 않는 것은 투자 대상을 고를 때마다 분류 칸을 다시 받지 않으려는 것이다.
 */
const GROUP_FETCH_LIMIT = 20

const LEAD_REQUEST: NewsRequest = { key: newsRequestKey([], NEWS_LIMITS.lead), sectorIds: [], limit: NEWS_LIMITS.lead }

/** 1위 묶음 카드에 처음 보이는 보도 수. 1위는 보도가 가장 많은 묶음이라 다 펼치면 카드가 옆 목록보다 한참 길어진다. */
const LEAD_REPORTS_SHOWN = 4

/** 012가 정한 상태를 사람 말로 옮긴다. 012가 문장을 주지 않았을 때만 쓴다. 판정을 더하지 않고 옮기기만 한다. */
const stateNotices: Record<Exclude<NewsDigestState, 'READY'>, string> = {
  EMPTY: '지금 보여 드릴 뉴스 묶음이 없습니다.',
  STALE: '표시한 뉴스가 최근 갱신분이 아닙니다.',
  PREPARING: '뉴스 묶음을 준비하고 있습니다.',
}

function sectorRequest(sectorId: string): NewsRequest {
  return { key: newsRequestKey([sectorId], SECTOR_FETCH_LIMIT), sectorIds: [sectorId], limit: SECTOR_FETCH_LIMIT }
}

function groupRequest(sectorIds: readonly string[]): NewsRequest {
  return { key: newsRequestKey(sectorIds, GROUP_FETCH_LIMIT), sectorIds, limit: GROUP_FETCH_LIMIT }
}

/** 처음 받는 중이면 `'pending'`이다. 실패했거나 다시 받는 중이면 빈 칸으로 보고 다른 칸을 정한다. */
function digestsOf(settled: NewsSettled | undefined): PartInput {
  if (!settled) return 'pending'
  return settled.status === 'ready' ? settled.result.digests : []
}

/** 발행 날짜를 한국 시각으로 적는다. 확인하지 못한 발행 시각은 확인한 것처럼 쓰지 않는다(012 계약). */
function publishedLabel(digest: Pick<NewsDigest, 'publicationTimeStatus' | 'firstPublishedAt'>): string {
  if (digest.publicationTimeStatus !== 'VERIFIED' || digest.firstPublishedAt === null) return '발행 시각 확인 안 됨'
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(digest.firstPublishedAt))
    .replace(/\.\s?/g, '.')
    .replace(/\.$/, '')
}

/** 보도 한 건의 발행 시각(월.일 시:분, 한국 시각). 확인하지 못했으면 그렇다고 적는다(012 US1 시나리오 3). */
function reportTimeLabel(article: Pick<NewsArticle, 'publicationTimeStatus' | 'publishedAt'>): string {
  if (article.publicationTimeStatus !== 'VERIFIED' || article.publishedAt === null) return '시각 확인 안 됨'
  const parts = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(article.publishedAt))
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? ''
  return `${part('month')}.${part('day')} ${part('hour')}:${part('minute')}`
}

function updatedLabel(value: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(value))
}

export function NewsSection() {
  const selection = useContext(SectorSelectionContext)
  const selectedCodes = selection?.selectedCodes
  const titleId = useId()
  const rootRef = useRef<HTMLElement>(null)
  const near = useNearViewport(rootRef)
  const [sectors, setSectors] = useState<NewsSector[] | null>(null)
  const [sectorsFailed, setSectorsFailed] = useState(false)
  const [sectorsAttempt, setSectorsAttempt] = useState(0)
  const [groupId, setGroupId] = useState<string | null>(null)
  const [sectorId, setSectorId] = useState<string | null>(null)
  // 칸을 처음 다 그렸는지. 그 뒤로는 새로 고른 섹터의 칸만 받는 중으로 두고 나머지를 그대로 둔다(`arrangeNews`).
  const [arrangedOnce, setArrangedOnce] = useState(false)

  useEffect(() => {
    if (!near) return
    const controller = new AbortController()
    fetchNewsSectors(controller.signal)
      .then((list) => {
        if (!controller.signal.aborted) setSectors(list)
      })
      .catch(() => {
        // 섹터 목록을 받지 못해도 주요 뉴스는 보인다. 고른 섹터·분류별 자리에 안내와 다시 시도를 둔다.
        if (controller.signal.aborted) return
        setSectors([])
        setSectorsFailed(true)
      })
    return () => controller.abort()
  }, [near, sectorsAttempt])

  const groups = useMemo(() => groupNewsSectors(sectors ?? []), [sectors])
  const names = useMemo(() => new Map((sectors ?? []).map((sector) => [sector.sectorId, sector.name])), [sectors])
  // 뉴스 섹터 목록에 있는 투자 대상만 묻는다. 없는 섹터를 물으면 012가 400으로 거절한다.
  const selectedSectors = useMemo(
    () => (selectedCodes ?? []).filter((code) => names.has(code)),
    [selectedCodes, names],
  )
  const openGroup = groups.find((group) => group.id === groupId) ?? null

  const requests = useMemo(() => {
    const list: NewsRequest[] = [LEAD_REQUEST]
    if (sectorId) list.push(sectorRequest(sectorId))
    for (const code of selectedSectors) list.push(sectorRequest(code))
    for (const group of groups) list.push(groupRequest(group.sectors.map((sector) => sector.sectorId)))
    return list
  }, [sectorId, selectedSectors, groups])
  const { settled, retry } = useNewsDigestBatch(requests, near)

  const lead = settled[LEAD_REQUEST.key]
  const groupKeyOf = (id: string) =>
    groupRequest(groups.find((group) => group.id === id)?.sectors.map((sector) => sector.sectorId) ?? []).key
  const arranged = arrangeNews(
    {
      lead: digestsOf(lead),
      selected: sectors === null ? 'pending' : selectedSectors.map((code) => ({ code, digests: digestsOf(settled[sectorRequest(code).key]) })),
      groups: sectors === null ? 'pending' : groups.map((group) => ({ id: group.id, digests: digestsOf(settled[groupKeyOf(group.id)]) })),
    },
    { holdUntilComplete: !arrangedOnce },
  )
  const fullyArranged = arranged.selected !== null && arranged.groups !== null
  useEffect(() => {
    if (fullyArranged) setArrangedOnce(true)
  }, [fullyArranged])

  const leadResult = lead?.status === 'ready' ? lead.result : null
  const notice = leadResult && leadResult.state !== 'READY' ? leadResult.statusMessage ?? stateNotices[leadResult.state] : null
  // 뉴스가 하나도 없다(012가 EMPTY·PREPARING이라 했다). 012의 문장만 두고 부분과 칸을 늘어놓지 않는다.
  const nothingToShow = leadResult !== null && (leadResult.state === 'EMPTY' || leadResult.state === 'PREPARING')

  return (
    <section ref={rootRef} className={styles.section} aria-labelledby={titleId}>
      <div className={styles.head}>
        {/*
          갱신 주기는 제목 옆 i 표시 뒤에 둔다. 주기는 백엔드 수집 설정(thorp.news.collection-interval, 기본 PT15M)을 따르며
          API가 주기를 내주지 않아 여기 문장에 적는다 — 설정을 바꾸면 이 문장도 함께 고친다. 오래된 뉴스를 '실시간'이라
          부르지 않는다(012 FR-014).
        */}
        <div className={styles.titleGroup}>
          <h2 id={titleId} className={styles.title}>
            <Newspaper size={16} aria-hidden="true" />
            지금 시장 뉴스
          </h2>
          <InlineHelp label="뉴스 갱신" mark="i" side="bottom">
            <p>뉴스는 약 15분마다 새로 모아 같은 사건끼리 묶습니다.</p>
            <p>수집이 늦어지면 마지막으로 모은 뉴스를 그대로 보여 드립니다.</p>
          </InlineHelp>
        </div>
        {leadResult?.lastUpdatedAt ? <p className={styles.updated}>마지막 갱신 {updatedLabel(leadResult.lastUpdatedAt)} KST</p> : null}
      </div>
      {notice ? <p className={styles.notice}>{notice}</p> : null}

      {nothingToShow ? null : (
        <>
          {groups.length > 0 ? (
            <>
              <div className={styles.groups} role="group" aria-label="분류별로 보기">
                <button
                  type="button"
                  className={styles.group}
                  aria-pressed={openGroup === null && sectorId === null}
                  onClick={() => {
                    setGroupId(null)
                    setSectorId(null)
                  }}
                >
                  전체
                </button>
                {groups.map((group) => (
                  <button
                    key={group.id}
                    type="button"
                    className={styles.group}
                    aria-pressed={openGroup?.id === group.id}
                    onClick={() => {
                      // 다른 분류를 열면 앞 분류에서 좁혀 둔 섹터를 푼다. 남겨 두면 '금융'이 눌린 채 반도체 뉴스가 보인다.
                      setGroupId(group.id)
                      setSectorId(null)
                    }}
                  >
                    {group.name}
                  </button>
                ))}
              </div>
              {openGroup ? (
                <div className={styles.filter} role="group" aria-label={`${openGroup.name} 섹터별로 보기`}>
                  {openGroup.sectors.map((sector) => (
                    <button
                      key={sector.sectorId}
                      type="button"
                      className={styles.chip}
                      aria-pressed={sectorId === sector.sectorId}
                      onClick={() => setSectorId(sectorId === sector.sectorId ? null : sector.sectorId)}
                    >
                      {sector.name}
                    </button>
                  ))}
                </div>
              ) : null}
            </>
          ) : null}

          {sectorId ? (
            <NarrowedPart
              name={names.get(sectorId) ?? sectorId}
              settled={settled[sectorRequest(sectorId).key]}
              onRetry={() => retry(sectorRequest(sectorId).key)}
              onShowAll={() => {
                setSectorId(null)
                setGroupId(null)
              }}
            />
          ) : (
            <>
              <section className={styles.part} aria-label="주요 뉴스">
                <h3 className={styles.partTitle}>주요 뉴스</h3>
                {lead?.status === 'failed' ? (
                  <PartFailure onRetry={() => retry(LEAD_REQUEST.key)} />
                ) : arranged.lead === null || lead?.status === 'retrying' ? (
                  <p className={styles.loading} role="status">뉴스 묶음을 불러오는 중입니다.</p>
                ) : arranged.lead.first === null ? (
                  <p className={styles.empty}>{leadResult?.statusMessage ?? stateNotices.EMPTY}</p>
                ) : (
                  <div className={styles.leadGrid}>
                    <LeadStory key={arranged.lead.first.digestId} digest={arranged.lead.first} />
                    {arranged.lead.rest.length > 0 ? (
                      <ul className={styles.list} aria-label="그다음 주요 뉴스">
                        {arranged.lead.rest.map((digest) => <DigestItem key={digest.digestId} digest={digest} />)}
                      </ul>
                    ) : null}
                  </div>
                )}
              </section>

              {sectorsFailed ? (
                <section className={styles.part} aria-label="섹터별 뉴스">
                  <PartFailure
                    message="섹터별 뉴스를 지금은 불러올 수 없습니다."
                    onRetry={() => {
                      setSectorsFailed(false)
                      setSectors(null)
                      setSectorsAttempt((attempt) => attempt + 1)
                    }}
                  />
                </section>
              ) : null}

              {selectedSectors.length > 0 ? (
                <section className={styles.part} aria-label="고른 섹터 뉴스">
                  <h3 className={styles.partTitle}>고른 섹터 뉴스</h3>
                  {arranged.selected === null ? (
                    <p className={styles.loading} role="status">고른 섹터의 뉴스를 불러오는 중입니다.</p>
                  ) : (
                    <div className={styles.columns}>
                      {arranged.selected.map((part) => (
                        <Column
                          key={part.code}
                          title={names.get(part.code) ?? part.code}
                          part={part}
                          settled={settled[sectorRequest(part.code).key]}
                          onRetry={() => retry(sectorRequest(part.code).key)}
                        />
                      ))}
                    </div>
                  )}
                </section>
              ) : null}

              {groups.length > 0 ? (
                <section className={styles.part} aria-label="분류별 뉴스">
                  <h3 className={styles.partTitle}>분류별 뉴스</h3>
                  {arranged.groups === null ? (
                    <p className={styles.loading} role="status">분류별 뉴스를 불러오는 중입니다.</p>
                  ) : (
                    <div className={styles.columns}>
                      {arranged.groups.map((part) => (
                        <Column
                          key={part.id}
                          title={groups.find((group) => group.id === part.id)?.name ?? part.id}
                          part={part}
                          settled={settled[groupKeyOf(part.id)]}
                          onRetry={() => retry(groupKeyOf(part.id))}
                        />
                      ))}
                    </div>
                  )}
                </section>
              ) : null}
            </>
          )}
        </>
      )}
    </section>
  )
}

/**
 * 1위 묶음. 대표 제목·사실 요약·매체 수와 그 사건을 다룬 보도 목록(매체·발행 시각·원문 링크)을 함께 보인다(012 FR-001 ①,
 * US1 시나리오 3). 보도가 넷보다 둘 이상 많으면 넷만 보이고 나머지는 펼쳐 본다. 하나 더 많을 때 '1건 더 보기'를 두면 펼치는
 * 수고가 더 크다.
 */
function LeadStory({ digest }: { digest: NewsDigest }) {
  const reportsId = useId()
  const [allReports, setAllReports] = useState(false)
  const collapsible = digest.articles.length > LEAD_REPORTS_SHOWN + 1
  const reports = collapsible && !allReports ? digest.articles.slice(0, LEAD_REPORTS_SHOWN) : digest.articles
  return (
    <article className={styles.leadStory}>
      <a className={styles.leadTitle} href={digest.articles[0].originalUrl} target="_blank" rel="noreferrer noopener">
        {digest.representativeTitle}
        <span className="sr-only"> (새 창에서 열림)</span>
      </a>
      <p className={styles.summary}>{digest.factualSummary}</p>
      <p className={styles.meta}>매체 {digest.publisherCount}곳 · {publishedLabel(digest)}</p>
      <ul id={reportsId} className={styles.reports} aria-label="이 사건을 다룬 보도">
        {reports.map((article) => (
          <li key={article.articleId}>
            <a href={article.originalUrl} target="_blank" rel="noreferrer noopener">
              {article.publisher}
              <span className="sr-only"> 원문 (새 창에서 열림)</span>
            </a>
            <span className={styles.reportTitle}>{article.title}</span>
            <span className={styles.reportTime}>{reportTimeLabel(article)}</span>
          </li>
        ))}
      </ul>
      {collapsible ? (
        <button
          type="button"
          className={styles.moreReports}
          aria-expanded={allReports}
          aria-controls={reportsId}
          onClick={() => setAllReports(!allReports)}
        >
          {allReports ? '보도 접기' : `보도 ${digest.articles.length - LEAD_REPORTS_SHOWN}건 더 보기`}
        </button>
      ) : null}
    </article>
  )
}

function DigestItem({ digest, compact = false }: { digest: NewsDigest; compact?: boolean }) {
  return (
    <li className={styles.item}>
      <a className={styles.itemTitle} href={digest.articles[0].originalUrl} target="_blank" rel="noreferrer noopener">
        {digest.representativeTitle}
        <span className="sr-only"> (새 창에서 열림)</span>
      </a>
      <p className={compact ? `${styles.summary} ${styles.clamped}` : styles.summary}>{digest.factualSummary}</p>
      <p className={styles.meta}>매체 {digest.publisherCount}곳 · {publishedLabel(digest)}</p>
    </li>
  )
}

/**
 * 고른 섹터 하나 또는 분류 하나의 칸. 모자라면 있는 만큼만 보인다. 칸은 랜드마크가 아니라 제목을 단 묶음이다 — 칸마다 영역을
 * 두면 구역 하나에 영역이 열 개 넘게 생겨 영역 목록이 쓸모없어진다.
 */
function Column({ title, part, settled, onRetry }: {
  title: string
  part: ArrangedPart
  settled: NewsSettled | undefined
  onRetry: () => void
}) {
  const titleId = useId()
  return (
    <div className={styles.column} role="group" aria-labelledby={titleId}>
      <h4 id={titleId} className={styles.columnTitle}>{title}</h4>
      {settled?.status === 'failed' ? (
        <PartFailure onRetry={onRetry} />
      ) : part.digests === null || settled?.status === 'retrying' ? (
        <p className={styles.loading} role="status">뉴스를 불러오는 중입니다.</p>
      ) : part.digests.length === 0 ? (
        <p className={styles.empty}>
          {part.shownAbove
            ? '위에 이미 보인 뉴스뿐입니다.'
            : (settled?.status === 'ready' ? settled.result.statusMessage : null) ?? '지금 보여 드릴 뉴스가 없습니다.'}
        </p>
      ) : (
        <ul className={styles.list}>
          {part.digests.map((digest) => <DigestItem key={digest.digestId} digest={digest} compact />)}
        </ul>
      )}
    </div>
  )
}

function NarrowedPart({ name, settled, onRetry, onShowAll }: {
  name: string
  settled: NewsSettled | undefined
  onRetry: () => void
  onShowAll: () => void
}) {
  const digests = settled?.status === 'ready' ? settled.result.digests : []
  return (
    <section className={styles.part} aria-label={`${name} 뉴스`}>
      <div className={styles.partHead}>
        <h3 className={styles.partTitle}>{name} 뉴스</h3>
        <button type="button" className={styles.inlineAction} onClick={onShowAll}>전체 보기</button>
      </div>
      {settled === undefined || settled.status === 'retrying' ? (
        <p className={styles.loading} role="status">뉴스 묶음을 불러오는 중입니다.</p>
      ) : settled.status === 'failed' ? (
        <PartFailure onRetry={onRetry} />
      ) : digests.length === 0 ? (
        <p className={styles.empty}>{settled.result.statusMessage ?? '지금 보여 드릴 뉴스가 없습니다.'}</p>
      ) : (
        <ul className={styles.list}>
          {digests.map((digest) => <DigestItem key={digest.digestId} digest={digest} />)}
        </ul>
      )}
    </section>
  )
}

/** 조용한 실패 안내. 이 부분만 다시 받고, 투자 대상 고르기는 막지 않는다(012 US4 시나리오 3). */
function PartFailure({ message = '뉴스를 지금은 불러올 수 없습니다.', onRetry }: { message?: string; onRetry: () => void }) {
  return (
    <div className={styles.failure} role="status">
      <p>{message}</p>
      <button type="button" className={styles.inlineAction} onClick={onRetry}>다시 시도</button>
    </div>
  )
}
