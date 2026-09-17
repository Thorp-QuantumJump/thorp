package com.ssafy.thorp.sector.application.result;

import java.time.LocalDate;
import java.util.List;

/**
 * 공통 평가 가능 기간 산식의 재료 (contracts/evaluation-window.md 「입력」).
 *
 * <p>계약이 입력으로 적은 다섯 가지 가운데 <b>001이 저장소에서 읽을 수 있는 넷</b>이다. 남은 하나인 {@code
 * strategies[].warmupTradingDays}는 전략 등록표(003 소유)에서 오므로 여기 없다 — 001은 그 값을 정하지 않고 받는다.
 *
 * <p><b>한 덩어리로 읽는 이유</b>: 자료 버전을 고르는 일과 그 버전의 달력·부족 구간을 읽는 일이 따로 일어나면, 그 사이 새 자료 버전이 발행됐을 때 마감 시각은
 * 옛 버전의 것이고 달력은 새 버전의 것인 응답이 나온다. 저장소가 한 트랜잭션에서 이 덩어리를 만들어 돌려준다({@link SectorListResult}와 같은 판단이다).
 *
 * @param datasetId 이 재료를 만든 자료 버전. 응답에 실어 화면이 어느 시점 자료인지 알게 한다(FR-013)
 * @param dataDeadline {@code dataset_version.data_deadline}을 거래소 현지 날짜로 자른 값
 * @param candidates 요청한 섹터의 대표 ETF. <b>요청 순서를 보존한다</b> — 산식의 {@code bindingCandidate} 동점 규칙이 입력 순서를
 *     쓰기 때문이다
 * @param tradingCalendar 거래일 달력. 확보한 지수 자료({@code daily_index})의 날짜 집합이다
 */
public record EvaluationWindowInputs(
        String datasetId,
        LocalDate dataDeadline,
        List<Candidate> candidates,
        List<LocalDate> tradingCalendar) {

    public EvaluationWindowInputs {
        candidates = List.copyOf(candidates);
        tradingCalendar = List.copyOf(tradingCalendar);
    }

    /**
     * 후보 대표 ETF 하나. 출처는 {@code representative_etf}와 {@code data_gap}이다.
     *
     * <p>섹터 코드를 담지 않는다. 종목과 섹터를 잇는 것은 대응표이고 그것은 호출자가 이미 읽은 값이라, 저장소가 같은 조인을 한 번 더 하면 두 곳이 서로 다른 대응표
     * 버전을 볼 여지가 생긴다.
     *
     * @param delistedOn 상장폐지일. 현재 상장 상품이면 null이다
     */
    public record Candidate(
            String instrumentId, LocalDate listedOn, LocalDate delistedOn, List<DataGap> dataGaps) {

        public Candidate {
            dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        }
    }

    /** 후보 하나의 자료 부족 구간. {@code reason}은 {@code data_gap.reason} 값이다(FR-006). */
    public record DataGap(LocalDate start, LocalDate end, String reason) {}
}
