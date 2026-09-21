package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.domain.model.AvailableRange;
import com.ssafy.thorp.regimelink.domain.model.FilterDefinition;
import com.ssafy.thorp.regimelink.domain.model.InstrumentHistory;
import com.ssafy.thorp.regimelink.domain.model.LinkFilter;
import com.ssafy.thorp.regimelink.domain.vo.FilterKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code regime_link_filter} 행과 도메인 사이의 변환.
 *
 * <p>정의를 JSON 으로 옮기는 모양은 필터 키를 만드는 모양과 <b>같아야 한다</b> — 저장 모양과 해시 입력이 갈리면 같은 행을 다시 읽어 키를 다시 만들었을 때
 * 값이 달라진다. 그래서 키 계산기가 만든 맵을 그대로 저장한다.
 */
final class RegimeLinkFilterMapper {

    private RegimeLinkFilterMapper() {}

    /** 저장 컬럼에 들어가는 가용 범위. 저장소가 네이티브 INSERT 로 넣으므로 밖에서도 쓴다. */
    static Map<String, Object> range(LinkFilter filter) {
        return range(filter.availableRange());
    }

    static LinkFilter toDomain(RegimeLinkFilterJpaEntity entity) {
        return new LinkFilter(
                entity.getId(),
                new FilterKey(entity.getFilterKey()),
                definition(entity.getDefinition()),
                range(entity.getAvailableRange()),
                entity.getCreatedAt());
    }

    /**
     * 저장된 정의의 정수 필드. 없으면 언박싱 NPE 대신 무엇이 빠졌는지 말한다.
     *
     * <p>이 컬럼은 저장할 때 늘 채우므로 비어 있다는 것은 손으로 넣었거나 모양이 바뀐 행이라는 뜻이다. 그때 조회 전체가 NPE 로 죽으면 원인이 필터가 아니라 판정
     * 쪽에 있는 것처럼 보인다.
     */
    private static int required(Map<String, Object> source, String key) {
        Integer value = JsonValues.integer(source, key);
        if (value == null) {
            throw new IllegalStateException("필터 정의에 " + key + " 가 없습니다.");
        }
        return value;
    }

    private static Map<String, Object> range(AvailableRange range) {
        Map<String, Object> map = JsonValues.map();
        JsonValues.put(map, "firstCaseEndMonth", range.firstCaseEndMonth());
        JsonValues.put(map, "lastCaseEndMonth", range.lastCaseEndMonth());
        return map;
    }

    private static AvailableRange range(Map<String, Object> source) {
        return new AvailableRange(
                JsonValues.month(source, "firstCaseEndMonth"),
                JsonValues.month(source, "lastCaseEndMonth"));
    }

    /**
     * 저장된 정의를 도메인으로 되돌린다.
     *
     * <p>{@code instruments} 에는 키에 들어가는 두 값만 있으므로 섹터와 상장폐지일은 비어 있다 — 그 둘은 판정할 때 지금 자료에서 다시 읽는다.
     */
    private static FilterDefinition definition(Map<String, Object> source) {
        List<InstrumentHistory> instruments = new ArrayList<>();
        for (Map<String, Object> row : JsonValues.objects(source, "instruments")) {
            instruments.add(
                    new InstrumentHistory(
                            null,
                            JsonValues.text(row, "instrumentId"),
                            JsonValues.date(row, "listedOn"),
                            null));
        }
        return new FilterDefinition(
                instruments,
                required(source, "maxWarmupTradingDays"),
                required(source, "minPostTradingDays"),
                JsonValues.text(source, "mappingVersion"),
                JsonValues.text(source, "dataVersion"),
                JsonValues.date(source, "dataAsOf"),
                JsonValues.date(source, "postCutoff"));
    }
}
