package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.port.BacktestRunReadPort;
import com.ssafy.thorp.regimelink.application.port.BundleRunView;
import com.ssafy.thorp.regimelink.application.port.RunProgressView;
import com.ssafy.thorp.regimelink.application.port.RunSlice;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 판정 시험용 비교 묶음. 토큰을 풀지 않고 정해 둔 묶음을 그대로 돌려준다.
 *
 * <p>구간 읽기·진행 조회는 판정 시험이 쓰지 않으므로 비어 있다 — 채워 두면 시험이 확인하지 않는 값을 근거로 통과할 수 있다.
 */
public class FakeBacktestRuns implements BacktestRunReadPort {

    private final List<BundleRunView> bundle;
    private final Map<Long, RunSlice> slices = new HashMap<>();

    public FakeBacktestRuns(List<BundleRunView> bundle) {
        this.bundle = List.copyOf(bundle);
    }

    public FakeBacktestRuns slice(Long runId, RunSlice slice) {
        slices.put(runId, slice);
        return this;
    }

    @Override
    public List<BundleRunView> resolveBundle(String baseToken, String compareToken) {
        return bundle;
    }

    @Override
    public List<BundleRunView> resolveBundleByRequest(
            Long baseRunId, Long baseRequestId, Long compareRunId, Long compareRequestId) {
        return bundle;
    }

    @Override
    public Optional<BundleRunView> findRun(Long runId) {
        return bundle.stream().filter(run -> runId.equals(run.runId())).findFirst();
    }

    @Override
    public Optional<RunSlice> readSlice(
            Long runId, LocalDate baselineDate, LocalDate d0, LocalDate lastAvailable) {
        return Optional.ofNullable(slices.get(runId));
    }

    @Override
    public Optional<RunProgressView> readProgress(Long runId) {
        return Optional.empty();
    }
}
