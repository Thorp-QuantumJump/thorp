# 시장·거시 지표 개별 조회 API

## 범위

지표 하나당 독립된 GET API로 최신값을 조회한다. 인증 불필요, 요청 파라미터·본문 없음.
외부 경로는 `/api/markets/{slug}`, 현재 Vite/운영 프록시가 `/api`를 제거하므로 Spring 매핑은 `/markets/{slug}`이다.
프론트는 카드별로 호출·로딩·오류·재시도를 관리한다. 이번 변경은 백엔드 연동이며 기존 목업 UI 연결은 별도 작업이다.

| slug | code | 원천 / 조회 조건 |
|---|---|---|
| kospi | KOSPI | KRX kospi_dd_trd / 코스피 |
| kosdaq | KOSDAQ | KRX kosdaq_dd_trd / 코스닥 |
| usd-krw | USD_KRW | ECOS 731Y001 / 0000001 / D |
| vkospi200 | VKOSPI200 | KRX drvprod_dd_trd / IDX_NM 설정값, 승인 후 검증 필요 |
| ktb-3y | KTB_3Y | ECOS 817Y002 / 010200000 / D |
| coincident-cycle | COINCIDENT_CYCLE | KOSIS 101 / DT_1C8015 / T1 / B03 / M |
| leading-cycle | LEADING_CYCLE | KOSIS 101 / DT_1C8015 / T1 / A03 / M |
| esi-cycle | ESI_CYCLE | ECOS 513Y001 / E2000 / M |
| core-cpi-yoy | CORE_CPI_YOY | KOSIS 101 / DT_1J22042 / T03 / 4 / M |
| exports-usd | EXPORTS_USD | 관세청 Newtrade/getNewtradeList / expDlr(달러) |
| m2 | M2 | ECOS 161Y006 / BBHA00 / M / 십억원, 평잔·원계열 |

KOSIS 코드는 공식 메타데이터로 확인했다. 사용자의 키로 데이터 조회 성공 여부는 별도 검증한다.
ECOS 과거 101Y003은 현재 M2 평잔·원계열 정의와 다르므로 사용하지 않는다.

## 응답

시장 API는 `HttpApiResponse`로 `{httpStatus, isSuccess, message, code, result}`를 반환한다.
기존 backtest의 `ApiResponse`는 기존 클라이언트 호환성을 위해 유지한다. 전체 응답 통일은 별도 마이그레이션이다.

성공 HTTP 200, result는 배열이 아닌 지표 객체 하나다.

```json
{
  "httpStatus": "OK",
  "isSuccess": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "code": 200,
  "result": {
    "code": "USD_KRW",
    "name": "원/달러 환율",
    "group": "market",
    "value": 1355.2,
    "unit": "원/USD",
    "frequency": "daily",
    "observationDate": "2026-09-07",
    "retrievedAt": "2026-09-07T08:00:00Z",
    "source": {"provider": "한국은행 ECOS", "datasetName": "원/미국달러(매매기준율)"},
    "status": "ok",
    "statusMessage": null
  }
}
```

위 응답은 형식 설명용 예시다. 월 자료는 YYYY-MM, 일 자료는 YYYY-MM-DD, 수집 시각은 UTC ISO 8601.
갱신 실패 후 유효한 이전 값이 있으면 200 + status=stale, 원래 retrievedAt과 관측일을 유지한다.
키 미설정·원천 인증 실패·결측·타임아웃 등으로 사용할 값이 없으면 503, result=null.
내부 오류는 500. 허용되지 않는 HTTP 메서드(405) 등 컨트롤러 매칭 전 공통 오류는 기존 ApiResponse 형식을 사용한다. 외부 인증 실패를 호출자의 401로 전달하지 않는다.
실패 시 키·원천 URL·오류 원문을 응답 또는 로그에 남기지 않는다.

## 조회·캐시 정책

- 지표별 6시간 메모리 캐시, 수집 후 총 24시간 미만의 이전 값만 stale로 허용한다.
- 같은 지표의 동시 갱신을 직렬화하고 서로 다른 지표는 독립적으로 실행한다.
- 실패 후 30초 동안 외부 재호출을 억제한다. 실패 쿨다운은 설정 가능하다.
- 전체 시간 예산은 8초. 연결 2초, 개별 HTTP 요청 3초, 여러 날짜를 조회해도 남은 예산 내에서만 호출한다.
- ECOS는 최근 일별 60일/월별 24개월에서 최신 유효값을 찾는다. 조회 범위는 100행을 넘지 않는다.
- KRX는 최근 14일 이내 평일을 역순으로 조회한다. 빈 자료만 이전 날짜로 이동하며 인증 실패는 즉시 반환한다.
- KOSIS는 해당 항목의 최근 3개 수록시점에서 최신값을 고른다. CPI 전년동월비를 직접 사용한다.
- 관세청은 완료된 최근 12개월을 조회하며 총계 행은 제외한다. expDlr은 달러 단위이다.
- 결측·숫자 파싱 실패를 0으로 바꾸지 않는다. 목업 값으로 대체하지 않는다.

## 최근 흐름 API (series)

첫 화면의 지표 차트가 실제 일별 흐름을 그리도록, 일별 지표 네 개는 최근 거래일 종가 목록을 따로 준다.
최신값 API와 같은 `HttpApiResponse` 형식이고 인증이 필요 없다.

| 경로 | code | 원천 |
|---|---|---|
| `/markets/kospi/series` | KOSPI | KRX kospi_dd_trd (날짜별 호출) |
| `/markets/kosdaq/series` | KOSDAQ | KRX kosdaq_dd_trd (날짜별 호출) |
| `/markets/usd-krw/series` | USD_KRW | ECOS 731Y001 / 0000001 / D (기간 한 번 호출) |
| `/markets/vkospi200/series` | VKOSPI200 | KRX drvprod_dd_trd (날짜별 호출) |

```json
{
  "code": "KOSPI",
  "name": "KOSPI 지수",
  "unit": "pt",
  "frequency": "daily",
  "points": [{"date": "2026-06-24", "value": 2688.1}, {"date": "2026-06-25", "value": 2701.4}],
  "retrievedAt": "2026-09-23T03:00:00Z",
  "source": {"provider": "KRX", "datasetName": "KOSPI 시리즈 일별시세정보"},
  "status": "ok",
  "statusMessage": null
}
```

- `points`는 날짜 오름차순이고 최대 60개다(거래일 기준). 결측·파싱 실패 날짜는 빼며 0으로 채우지 않는다.
- **요청은 외부 기관을 부르지 않는다.** KRX는 날짜 하나당 한 번 호출이라 60일을 요청마다 모으면 8초 예산을
  넘는다. 백엔드가 기동 직후와 `market.series.refresh-interval`(기본 6시간)마다 백그라운드로 모아 두고, 요청은
  모아 둔 값만 돌려준다.
- KRX는 이미 모은 날짜를 다시 부르지 않고 빠진 평일만 부른다. 호출 사이에 `market.series.krx-pace`(기본 150ms)를
  두고, 한 번의 갱신은 `market.series.refresh-budget`(기본 90초) 안에서 멈춘다. 모자란 날짜는 다음 갱신이 잇는다.
- ECOS는 최근 90일 범위를 한 번에 읽는다(100행 이하).
- 아직 모은 값이 없으면 503 + `result=null`이다. 마지막 성공이 `cacheMaxAge`(24시간)보다 오래되면 200 +
  `status=stale`로 모아 둔 값을 준다. 프론트는 503이면 예시 흐름을 쓰고 그렇다고 밝힌다.

## 실행

저장소 루트 `.env`에 ECOS_API_KEY, KRX_API_KEY, KOSIS_API_KEY, DATA_GO_KR_SERVICE_KEY를 설정한다.
KOSIS 키 끝의 `=`는 그대로 보존한다. KRX 키는 AUTH_KEY 헤더, 나머지 키는 기관이 요구하는 경로·쿼리에 전달된다.
MarketConfig가 로컬 `.env`를 로딩하며 환경변수가 우선한다. 공통 application.yml과 Docker Compose는 수정하지 않는다. Docker 실행 시 키를 컨테이너 환경변수로 별도 전달해야 한다.

```powershell
cd backend
.\gradlew.bat bootRun
Invoke-RestMethod http://localhost:8080/markets/usd-krw
```

전체 앱 실행에는 기존 PostgreSQL 환경이 필요하다.
단위·컨트롤러·아키텍처 검증: `.\gradlew.bat spotlessApply build`.
실제 기관 호출 검증은 opt-in 테스트로 수행하며 키와 원문 URL은 출력하지 않는다.

## 공식 근거

- [ECOS API](https://ecos.bok.or.kr/api/): StatisticTableList, StatisticItemList, StatisticSearch 실제 응답 확인
- [KRX 이용방법](https://openapi.krx.co.kr/contents/OPP/INFO/OPPINFO003.jsp): 인증키 및 서비스별 활용 승인
- [KOSIS 통계자료](https://kosis.kr/openapi/devGuide/devGuide_0201List.do): Param/statisticsParameterData.do
- [KOSIS 통계표설명](https://kosis.kr/openapi/devGuide/devGuide_060101List.do): getMeta/type=ITM 코드 확인
- [관세청 수출입총괄(GW)](https://www.data.go.kr/data/15102108/openapi.do): 페이지 내 Swagger의 Newtrade/getNewtradeList, expDlr

## 실제 호출 검증 (2026-09-07)

현재 `.env`를 로딩한 Spring 웹 서버의 11개 경로를 HTTP로 호출했다.
일반 테스트/아키텍처/Spotless/빌드 통과, opt-in 실제 호출 테스트도 통과했다.
실제 호출 테스트는 성공과 예상된 503 처리를 모두 검사하며, 11개 원천 수집이 모두 성공했다는 의미는 아니다.

| 코드 | 내부 HTTP | 확인 결과 |
|---|---|---|
| USD_KRW | 200 | 1355.2 원/USD, 2026-09-07 |
| KTB_3Y | 200 | 3.9 %, 2026-09-07 |
| ESI_CYCLE | 200 | 96.9 지수, 2026-08 |
| M2 | 200 | 4209874 십억원, 2026-06 |
| KOSPI / KOSDAQ / VKOSPI200 | 503 | 원천 인증 거부. KRX 키·서비스별 활용 승인 확인 필요 |
| COINCIDENT_CYCLE / LEADING_CYCLE / CORE_CPI_YOY | 503 | KOSIS 키 인증 거부. 끝의 =는 인코딩하여 보존됨 |
| EXPORTS_USD | 503 | DATA_GO_KR_SERVICE_KEY 미설정 |

KRX의 V-KOSPI 200 식별자는 승인 후 실데이터로 최종 확인해야 한다.
관세청 파서는 공식 Swagger에 근거한 구현이며 현재 키가 없어 실제 수집 성공은 검증하지 못했다.
키 변경 후에는 백엔드를 재시작한다. 승인만 변경된 경우 실패 쿨다운 30초 뒤 재호출한다.

```powershell
# backend 디렉터리, 실제 외부 API 호출을 명시적으로 활성화
$env:MARKET_LIVE_TEST='true'
.\gradlew.bat test --tests 'com.ssafy.thorp.market.MarketLiveApiTest' --rerun-tasks
Remove-Item Env:MARKET_LIVE_TEST
```

키를 포함하지 않는 결과는 backend/build/reports/market-live.json에 기록한다.

## 인증 문제 재검증 (2026-09-07 17:43 KST)

KRX 3개는 모두 실제 호출 성공. 관세청 실패는 `.env`의 Encoding 키를 다시 URL 인코딩하는 것이 원인이었다.
CustomsMarketClient에서 퍼센트 인코딩된 키를 한 번 정규화해 Encoding/Decoding 형식을 모두 지원하도록 수정했다.
회귀 테스트 2개와 전체 빌드, 실제 호출 검증 통과. 수출액은 HTTP 200, 2026-08 기준 98254664934 USD를 반환했다.
현재 11개 중 8개 성공이며 KOSIS 3개만 실패한다. KOSIS는 자료·메타데이터 모두 원천 오류 11(유효하지 않은 인증KEY)을 반환한다.
KOSIS 키 끝의 =는 보존되며 이중 인코딩도 발견되지 않았다. 발급된 키 전체와 활성 상태 확인이 필요하다.

## 최종 연결 확인 (2026-09-07 17:47 KST)

현재 키로 11개 API 모두 HTTP 200 및 실제 관측값 반환을 확인했다.
KOSIS 동행 101.2(2026-07), 선행 104.2(2026-07), 근원물가 3.4 % YoY(2026-08).
근원물가는 원천 UNIT_NM이 null이라 기존 단위 검증에 실패했다.
정확한 통계표·항목 코드·분류 코드와 항목명 전년동월비(%) 및 분류명 식료품및에너지제외지수를 확인한 경우에만 % YoY로 정규화하도록 수정했다.
잘못된 항목명을 거부하는 회귀 테스트와 전체 빌드·실제 호출 테스트가 통과했다.
위의 승인 대기·인증 실패 기록은 이전 검증 시점의 상태이며 현재는 해소되었다.

## 공통 파일 원복

application.yml, docker-compose.dev.yml, GlobalExceptionHandler.java의 변경을 원복했다.
.env 읽기와 ECOS_API_KEY 등 기존 키 이름의 연결은 market/infrastructure/config/MarketConfig에서 처리한다.
시장 API의 성공·원천 오류 응답은 유지하고, 컨트롤러 매칭 전 405 등은 기존 공통 응답을 사용한다.
