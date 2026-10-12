/**
 * 관리 화면이 서버 시각(ISO 8601)을 보이는 한 가지 방식이다. 목록의 마지막 게시와 단계 공개 시각이 함께 쓴다.
 * 읽지 못한 값은 받은 그대로 보인다. 지우면 서버가 무엇을 보냈는지 알 수 없다(원칙 VI).
 */
export function formatAdminTime(value: string): string {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('ko-KR')
}
