/**
 * '나의 학습' 그림(도장·메달·표장·레벨 길·정거장)의 좌표와 길이를 소수 둘째 자리로 줄인다. 그림 속성 글자를 짧게 두고
 * 같은 값이 늘 같은 글자로 그려지게 한다. 0.01은 100 단위 그림판(`viewBox`)에서도, 길 폭의 %로 놓는 자리에서도 화면
 * 1px보다 훨씬 작다.
 */
export function roundCoord(value: number): number {
  return Math.round(value * 100) / 100
}
