/**
 * 배우기 화면의 주소(013). 글 주소와 배우기 목록의 단계 자리 주소를 이 모듈 하나가 만든다.
 *
 * <p>단계 자리는 주소 조각 `#stage-n`이다. 공개 안내의 '○단계 보기'와 글 머리의 단계 연결이 같은 주소를 쓰고
 * (T139·T203), 배우기 목록이 이 조각을 읽어 그 단계 자리로 옮긴다.
 */

/** 배우기 목록 주소. */
export const LEARN_PATH = '/learn'

/** 글 하나의 주소. 글 식별자는 주소 조각으로 안전하게 바꾼다. */
export function articlePath(articleId: string): string {
  return `${LEARN_PATH}/${encodeURIComponent(articleId)}`
}

/** 단계 자리의 주소 조각. */
export function stageHash(stageNumber: number): string {
  return `#stage-${stageNumber}`
}

/** 배우기 목록의 그 단계 자리. `Link`의 `to`로 그대로 쓴다. */
export function stageLocation(stageNumber: number): { pathname: string; hash: string } {
  return { pathname: LEARN_PATH, hash: stageHash(stageNumber) }
}

/** `#stage-3`에서 단계 번호를 읽는다. 다른 모양이면 null이다. */
export function stageNumberFromHash(hash: string): number | null {
  const match = /^#stage-(\d{1,3})$/.exec(hash)
  return match ? Number(match[1]) : null
}
