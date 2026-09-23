package com.ssafy.thorp.regimelink.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 비교 묶음의 두 실행이 <b>같은 조건</b>인지 서버가 확인한다 (T093, SC-003·FR-011).
 *
 * <p>005는 이 판정을 화면에서만 한다. 011의 {@code base}·{@code compare}는 주소로 직접 들어올 수 있어 화면을 거치지 않으므로, 어긋난 묶음을
 * 그대로 받으면 조건이 다른 두 결과를 나란히 놓고 "같은 조건에서 비교했다"고 보이게 된다(원칙 IV).
 *
 * <p>어긋나면 <b>계산도 기록도 만들지 않고</b> 400 으로 거절하며 어느 항목이 어긋났는지 말한다. 성과에 의존하는 값은 보지 않는다 — 여기서 묻는 것은 "같은
 * 조건인가"이지 "결과가 비슷한가"가 아니다.
 */
@Component
public class BundleConsistencyChecker {

    /** 조건이 같은지 가리는 항목. 화면이 사용자에게 무엇이 다른지 말할 수 있도록 이름을 그대로 내보낸다. */
    private static final String SECTORS = "sectors";

    private static final String MAPPING_VERSION = "mappingVersion";
    private static final String INITIAL_CASH = "initialCash";
    private static final String COST_PROFILE_VERSION = "costProfileVersion";
    private static final String DATA_VERSION = "dataVersion";

    /**
     * 어긋난 항목이 있으면 {@code 400 INVALID_INPUT}.
     *
     * <p>비교 대상이 없는 묶음(기준 하나)은 견줄 상대가 없으므로 언제나 통과한다.
     */
    public void verify(List<BundleRunView> runs) {
        if (runs.size() < 2) {
            return;
        }
        BundleRunView base = runs.get(0);
        List<String> mismatched = new ArrayList<>();
        for (BundleRunView other : runs.subList(1, runs.size())) {
            add(mismatched, SECTORS, !sectors(base).equals(sectors(other)));
            add(
                    mismatched,
                    MAPPING_VERSION,
                    !Objects.equals(base.mappingVersion(), other.mappingVersion()));
            add(mismatched, INITIAL_CASH, !sameAmount(base.initialCash(), other.initialCash()));
            add(
                    mismatched,
                    COST_PROFILE_VERSION,
                    !Objects.equals(base.costProfileVersion(), other.costProfileVersion()));
            add(mismatched, DATA_VERSION, !Objects.equals(base.dataVersion(), other.dataVersion()));
        }
        if (!mismatched.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "비교 묶음의 조건이 서로 다릅니다: " + String.join(", ", new TreeSet<>(mismatched)));
        }
    }

    /** 섹터 후보 집합. 순서는 조건이 아니므로 집합으로 견준다. */
    private static Set<String> sectors(BundleRunView run) {
        Set<String> sectors = new LinkedHashSet<>();
        for (BundleRunView.UniverseEntry entry : run.universe()) {
            sectors.add(entry.sectorId() == null ? entry.instrumentId() : entry.sectorId());
        }
        return sectors;
    }

    /** {@code 10000000} 과 {@code 10000000.00} 은 같은 금액이다 — 표기 차이로 거절하지 않는다. */
    private static boolean sameAmount(java.math.BigDecimal left, java.math.BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private static void add(List<String> mismatched, String name, boolean differs) {
        if (differs) {
            mismatched.add(name);
        }
    }
}
