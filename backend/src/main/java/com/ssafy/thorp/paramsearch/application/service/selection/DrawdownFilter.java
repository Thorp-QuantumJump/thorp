package com.ssafy.thorp.paramsearch.application.service.selection;

import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 손실 제약 필터(FR-013·FR-013d).
 *
 * <p>"가장 크게 줄었던 폭이 <b>기준 실행보다 크지 않은</b> 후보만 통과"다. 절대 문턱(MDD 20% 이하 같은 것)이 없는 상대 기준이라, 시장이 통째로 나빴던
 * 구간에서도 "기준보다 낫다"는 말이 뜻을 유지한다(FR-013).
 *
 * <p><b>기준 전략이 무엇인지 알지 못한 채 값만 쓴다</b>(FR-013d·FR-025). 기준 전략 ID는 접수 때 등록표에서 읽어 {@link
 * SelectionRule} 스냅샷에 박혔고, 여기서는 그 스냅샷이 "적용 대상인가"만 본다. 이 클래스에 전략 ID 리터럴이 없는 이유가 그것이다.
 *
 * <p>필터가 빠지는 경우가 둘이다. 어느 쪽이든 <b>후보를 떨어뜨리지 않고 그 사실을 결과에 표시한다</b> — 기준이 없다고 조용히 전부 통과시키면 화면은 필터가 걸린 줄
 * 안다(Edge Case).
 *
 * <ol>
 *   <li>등록표가 기준 전략 역할을 지정하지 않았다 → 스냅샷의 {@code drawdownFilter.type}이 {@code NOT_APPLIED}다
 *   <li>기준 실행이 실패해 비교할 값이 없다 → {@code baselineMaxDrawdown}이 {@code null}이다
 * </ol>
 */
@Component
public class DrawdownFilter {

    /** 기준 전략 역할이 지정되지 않은 묶음의 스냅샷 값(접수 시점에 박힌다). */
    private static final String NOT_APPLIED = "NOT_APPLIED";

    /**
     * 이 묶음에 필터를 적용할 수 있는가. {@code false}면 전 후보가 통과하고 응답의 {@code drawdownFilterApplied}가 {@code
     * false}가 된다.
     *
     * @param baselineMaxDrawdown 기준 실행의 가장 크게 줄었던 폭. 기준이 실패했으면 null
     */
    public boolean isApplicable(SelectionRule rule, BigDecimal baselineMaxDrawdown) {
        return !NOT_APPLIED.equals(rule.drawdownFilter().type()) && baselineMaxDrawdown != null;
    }

    /**
     * 후보가 손실 제약을 통과하는가.
     *
     * <p><b>부호가 아니라 폭으로 비교한다.</b> 엔진은 가장 크게 줄었던 폭을 음수로 주지만(-0.19), 그 규약이 바뀌어도 "더 크게 줄었다"의 뜻은 절댓값에
     * 있다. 부호를 가정하고 크기 비교를 하면 규약이 바뀐 날 필터가 정반대로 뒤집힌다.
     *
     * @return 기준보다 크게 줄지 않았으면 true. 같으면 통과다("보다 크지 않은")
     */
    public boolean passes(BigDecimal candidateMaxDrawdown, BigDecimal baselineMaxDrawdown) {
        if (candidateMaxDrawdown == null) {
            // 비교할 값이 없는 후보는 통과시키지 않는다. 0으로 보정하면 "손실 없음"이 되어 최상위가 된다(FR-011).
            return false;
        }
        return candidateMaxDrawdown.abs().compareTo(baselineMaxDrawdown.abs()) <= 0;
    }
}
