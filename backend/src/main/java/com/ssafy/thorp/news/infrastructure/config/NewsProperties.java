package com.ssafy.thorp.news.infrastructure.config;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 뉴스 도메인의 서버 전용 설정. 사용자는 어떤 값도 고르지 않는다(spec FR-008·FR-018).
 *
 * <p>수치의 근거는 specs/012-market-news-digest/plan.md다. HTTP 한도는 설정으로 더 엄격하게만 조일 수 있으며 기본값을 넘겨 완화하지
 * 않는다(spec FR-020).
 *
 * @param exposureMode 노출 모드. 클라이언트가 고를 수 없고 서버 설정으로만 정한다(spec FR-016).
 * @param staleThreshold 마지막 성공 시각이 이보다 오래되면 STALE로 판정한다(spec FR-014).
 * @param collectionInterval 수집 회차 시작 간격(spec FR-013).
 * @param leaseTtl 수집·정리 작업 점유의 만료 시간. 만료되면 다른 인스턴스가 회수한다(spec FR-022).
 * @param retentionDate 뉴스 데이터 일괄 삭제 기준일(spec FR-019).
 * @param retentionZone 삭제 기준일을 해석하는 시간대. 날짜만으로 해석하지 않는다.
 * @param maxHistoricalRangeDays 과거 조회 요청 구간 상한. 자료 존재를 보장하지 않는 별개 경계다.
 * @param maxPublishers 등록 매체 수 계획 상한. 검증된 운영 용량이 아니다.
 * @param maxDigests 한 응답의 묶음 수 상한(spec FR-001).
 * @param http 외부 호출 보안 경계.
 */
@ConfigurationProperties(prefix = "thorp.news")
public record NewsProperties(
        @DefaultValue("INTERNAL") ExposureMode exposureMode,
        @DefaultValue("30m") Duration staleThreshold,
        @DefaultValue("15m") Duration collectionInterval,
        @DefaultValue("15m") Duration leaseTtl,
        @DefaultValue("2026-11-07") LocalDate retentionDate,
        @DefaultValue("Asia/Seoul") String retentionZone,
        @DefaultValue("93") int maxHistoricalRangeDays,
        @DefaultValue("20") int maxPublishers,
        @DefaultValue("3") int maxDigests,
        @DefaultValue Http http) {

    /** 노출 모드. 공개 조회는 허가된 매체만 사용한다. */
    public enum ExposureMode {
        INTERNAL,
        PUBLIC
    }

    /**
     * 외부 수집 호출의 공통 보안 경계(spec FR-020).
     *
     * @param maxRedirects 따라갈 redirect 최대 횟수. hop마다 scheme·host를 다시 검사한다.
     * @param connectTimeout 연결 제한 시간.
     * @param requestTimeout 요청 전체 제한 시간.
     * @param maxResponseBytes 읽을 응답 본문 최대 바이트. 초과하면 그 응답을 격리한다.
     * @param allowedRssContentTypes RSS 응답에 허용하는 content-type.
     * @param allowedJsonContentTypes GDELT 응답에 허용하는 content-type.
     * @param gdeltHosts GDELT 호출에 허용하는 host. 매체 host는 registry가 정한다.
     */
    public record Http(
            @DefaultValue("3") int maxRedirects,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("20s") Duration requestTimeout,
            @DefaultValue("10485760") long maxResponseBytes,
            @DefaultValue({"application/rss+xml", "application/xml", "text/xml"})
                    Set<String> allowedRssContentTypes,
            @DefaultValue({"application/json"}) Set<String> allowedJsonContentTypes,
            @DefaultValue({"api.gdeltproject.org", "data.gdeltproject.org"})
                    List<String> gdeltHosts) {

        public Http {
            if (maxRedirects < 0 || maxRedirects > 3) {
                throw new IllegalArgumentException("maxRedirects는 0 이상 3 이하여야 한다: " + maxRedirects);
            }
            if (maxResponseBytes <= 0 || maxResponseBytes > 10_485_760L) {
                throw new IllegalArgumentException(
                        "maxResponseBytes는 0 초과 10MiB 이하여야 한다: " + maxResponseBytes);
            }
            if (connectTimeout.isNegative()
                    || connectTimeout.isZero()
                    || connectTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
                throw new IllegalArgumentException(
                        "connectTimeout은 0 초과 5초 이하여야 한다: " + connectTimeout);
            }
            if (requestTimeout.isNegative()
                    || requestTimeout.isZero()
                    || requestTimeout.compareTo(Duration.ofSeconds(20)) > 0) {
                throw new IllegalArgumentException(
                        "requestTimeout은 0 초과 20초 이하여야 한다: " + requestTimeout);
            }
            allowedRssContentTypes = Set.copyOf(allowedRssContentTypes);
            allowedJsonContentTypes = Set.copyOf(allowedJsonContentTypes);
            gdeltHosts = List.copyOf(gdeltHosts);
        }
    }

    public NewsProperties {
        if (maxHistoricalRangeDays <= 0) {
            throw new IllegalArgumentException(
                    "maxHistoricalRangeDays는 1 이상이어야 한다: " + maxHistoricalRangeDays);
        }
        if (maxDigests <= 0) {
            throw new IllegalArgumentException("maxDigests는 1 이상이어야 한다: " + maxDigests);
        }
        if (maxPublishers <= 0) {
            throw new IllegalArgumentException("maxPublishers는 1 이상이어야 한다: " + maxPublishers);
        }
        if (staleThreshold.isNegative() || staleThreshold.isZero()) {
            throw new IllegalArgumentException("staleThreshold는 0보다 커야 한다: " + staleThreshold);
        }
        if (leaseTtl.isNegative() || leaseTtl.isZero()) {
            throw new IllegalArgumentException("leaseTtl은 0보다 커야 한다: " + leaseTtl);
        }
    }

    /** 공개 노출 모드인지 여부. 공개 모드에서는 허가된 매체의 항목만 보인다(spec FR-016). */
    public boolean isPublic() {
        return exposureMode == ExposureMode.PUBLIC;
    }
}
