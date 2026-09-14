package com.ssafy.thorp.sector.infrastructure.persistence;

import com.ssafy.thorp.sector.application.result.InstrumentProfileResult;
import com.ssafy.thorp.sector.domain.model.InstrumentProfile;
import com.ssafy.thorp.sector.domain.model.InstrumentProfile.Holding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JPA Entity → 도메인 모델 변환 (ADR-0001).
 *
 * <p><b>{@code toEntity}가 없다.</b> 이유는 {@link SectorMapper}와 같다 — 대표 상품 정보의 원본은 {@code data/}
 * 파이프라인이고 백엔드에 쓰기 경로가 없다. 부를 일 없는 {@code toEntity}를 두면 "여기로 쓸 수 있다"는 잘못된 신호가 된다.
 *
 * <p>JSON을 푸는 일이 여기 있는 까닭: 도메인 모델에는 Jackson·JPA import를 두지 않는다({@code backend/CLAUDE.md}). 그래서
 * {@code jsonb}에서 읽은 {@code Map}을 도메인의 {@link Holding} 목록으로 옮기는 일은 infrastructure의 몫이다.
 */
final class InstrumentProfileMapper {

    /** {@code instrument_profile.holdings_overlap}이 담는 배열의 키. 파이프라인과 합의한 이름이다. */
    private static final String TOP_HOLDINGS = "topHoldings";

    private static final String HOLDING_NAME = "name";
    private static final String HOLDING_WEIGHT = "weight";

    private InstrumentProfileMapper() {}

    /** 대표 상품 정보 한 행을 유스케이스 출력으로 옮긴다. 대표 ETF와 자료 버전 메타를 함께 싣는다. */
    static InstrumentProfileResult toResult(InstrumentProfileJpaEntity profile) {
        DatasetVersionJpaEntity version = profile.getDatasetVersion();
        return new InstrumentProfileResult(
                SectorMapper.toDomain(profile.getEtf()),
                toDomain(profile),
                version.getDatasetId(),
                version.getDataDeadline(),
                version.isSample());
    }

    static InstrumentProfile toDomain(InstrumentProfileJpaEntity profile) {
        return new InstrumentProfile(
                profile.getIndexMethodologySummary(),
                toHoldings(profile.getHoldingsOverlap()),
                profile.isHoldingsAsOfCurrent(),
                // 저장된 문자열을 그대로 옮긴다. 자리수가 보존되므로 응답의 plain 표기와 저장값이 같다.
                new BigDecimal(profile.getAvgDailyValue3m()),
                profile.getSourceAttribution(),
                profile.getSurvivorshipNote());
    }

    /**
     * {@code {"topHoldings": [...]}}를 도메인 목록으로 옮긴다.
     *
     * <p>모양이 어긋나면 값을 지어내지 않고 {@link IllegalArgumentException}이다. 이 자리의 인자 출처는 DB뿐이므로 {@code
     * SectorExceptionHandler}가 그 예외를 500으로 옮긴다 — 클라이언트가 고칠 것이 없는 실패다.
     */
    private static List<Holding> toHoldings(Map<String, Object> overlap) {
        if (overlap == null || !(overlap.get(TOP_HOLDINGS) instanceof List<?> raw)) {
            throw new IllegalArgumentException("holdings_overlap must carry a topHoldings array");
        }
        List<Holding> holdings = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> holding)) {
                throw new IllegalArgumentException("topHoldings entry must be an object: " + item);
            }
            holdings.add(
                    new Holding(
                            text(holding.get(HOLDING_NAME), HOLDING_NAME),
                            // 비중도 문자열로 저장된다. jsonb는 숫자를 정규화해 자리수를 바꾼다.
                            new BigDecimal(text(holding.get(HOLDING_WEIGHT), HOLDING_WEIGHT))));
        }
        return holdings;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    "holdings_overlap." + field + " must be a string: " + value);
        }
        return text;
    }
}
