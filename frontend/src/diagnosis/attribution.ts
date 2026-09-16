/**
 * KRX 자료 출처 표시 문구다. **한 곳에만 둔다.**
 *
 * 원칙 X가 "한국거래소 자료를 쓰는 화면에는 출처를 표시한다"를 조건 없이 요구하는데,
 * `GET /sectors` 응답에는 `sourceAttribution`이 없다. 그 필드는
 * `GET /sectors/{code}/instrument-profile`에만 있다(001의 섹터 조회 계약).
 *
 * 값을 지어내는 것이 아니라 001이 이미 고정 값처럼 내보내는 문구를 옮겨 적은 것이다.
 * 대표 상품 정보 패널에서는 이 상수가 아니라 응답의 `sourceAttribution`을 쓴다.
 *
 * **후속 조치**: `GET /sectors`가 같은 필드를 주면 이 파일을 지우고 응답 값으로 바꾼다.
 * 계약 변경은 001이 소유하므로 002에서 고치지 않는다(research.md 결정 8).
 */
export const KRX_SOURCE_ATTRIBUTION = '한국거래소 통계정보'
