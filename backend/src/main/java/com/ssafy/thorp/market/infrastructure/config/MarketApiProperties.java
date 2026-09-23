package com.ssafy.thorp.market.infrastructure.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 비밀 키가 toString에 포함되지 않도록 @Data를 사용하지 않는다. */
@Getter
@Setter
@ConfigurationProperties(prefix = "market")
public class MarketApiProperties {
    private Duration cacheTtl = Duration.ofHours(6);
    private Duration cacheMaxAge = Duration.ofHours(24);
    private Duration failureCooldown = Duration.ofSeconds(30);
    private Duration requestBudget = Duration.ofSeconds(8);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);
    private final Ecos ecos = new Ecos();
    private final Krx krx = new Krx();
    private final Kosis kosis = new Kosis();
    private final Customs customs = new Customs();
    private final Series series = new Series();

    @Getter
    @Setter
    public static class Ecos {
        private String apiKey = "";
        private String baseUrl = "https://ecos.bok.or.kr/api";
        private String m2Table = "161Y006";
        private String m2Item = "BBHA00";
    }

    @Getter
    @Setter
    public static class Krx {
        private String apiKey = "";
        private String baseUrl = "https://data-dbg.krx.co.kr/svc/apis/idx";
        private int lookbackDays = 14;
        private String volatilityIndexName = "코스피 200 변동성지수";
    }

    @Getter
    @Setter
    public static class Kosis {
        private String apiKey = "";
        private String baseUrl = "https://kosis.kr/openapi/Param/statisticsParameterData.do";
        private String cycleTable = "DT_1C8015";
        private String coreCpiTable = "DT_1J22042";
    }

    /** 최근 흐름(series) API. 요청이 아니라 백그라운드 갱신이 원천을 부른다. */
    @Getter
    @Setter
    public static class Series {
        /** 돌려주는 거래일 수. */
        private int window = 60;

        /** 거래일 60개를 덮는 달력 날짜 수. 공휴일이 몰려도 모자라지 않게 넉넉히 잡는다. */
        private int lookbackDays = 95;

        private Duration refreshBudget = Duration.ofSeconds(90);
        private Duration krxPace = Duration.ofMillis(150);
    }

    @Getter
    @Setter
    public static class Customs {
        private String apiKey = "";
        private String baseUrl = "https://apis.data.go.kr/1220000/Newtrade/getNewtradeList";
    }
}
