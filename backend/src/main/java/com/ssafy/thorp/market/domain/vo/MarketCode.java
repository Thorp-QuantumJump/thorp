package com.ssafy.thorp.market.domain.vo;

public enum MarketCode {
    KOSPI("KOSPI 지수", "market", "daily", "KRX", "KOSPI 시리즈 일별시세정보"),
    KOSDAQ("KOSDAQ 지수", "market", "daily", "KRX", "KOSDAQ 시리즈 일별시세정보"),
    USD_KRW("원/달러 환율", "market", "daily", "한국은행 ECOS", "원/미국달러(매매기준율)"),
    VKOSPI200("V-KOSPI 200", "market", "daily", "KRX", "파생상품지수 시세정보"),
    KTB_3Y("국고채 3년 금리", "market", "daily", "한국은행 ECOS", "국고채(3년)"),
    COINCIDENT_CYCLE("동행지수 순환변동치", "macro", "monthly", "KOSIS", "경기종합지수"),
    LEADING_CYCLE("선행지수 순환변동치", "macro", "monthly", "KOSIS", "경기종합지수"),
    ESI_CYCLE("경제심리지수 순환변동치", "macro", "monthly", "한국은행 ECOS", "경제심리지수(순환변동치)"),
    CORE_CPI_YOY("근원 소비자물가 상승률", "macro", "monthly", "KOSIS", "월별 소비자물가 등락률(식료품및에너지제외지수)"),
    EXPORTS_USD("월간 수출액", "macro", "monthly", "관세청", "수출입총괄"),
    M2("광의통화 M2", "macro", "monthly", "한국은행 ECOS", "M2(평잔, 원계열)");

    private final String displayName;
    private final String group;
    private final String frequency;
    private final String provider;
    private final String datasetName;

    MarketCode(
            String displayName,
            String group,
            String frequency,
            String provider,
            String datasetName) {
        this.displayName = displayName;
        this.group = group;
        this.frequency = frequency;
        this.provider = provider;
        this.datasetName = datasetName;
    }

    public String displayName() {
        return displayName;
    }

    public String group() {
        return group;
    }

    public String frequency() {
        return frequency;
    }

    public String provider() {
        return provider;
    }

    public String datasetName() {
        return datasetName;
    }
}
