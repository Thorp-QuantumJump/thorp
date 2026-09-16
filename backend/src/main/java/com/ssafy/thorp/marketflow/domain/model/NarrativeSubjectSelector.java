package com.ssafy.thorp.marketflow.domain.model;

import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 최근 세 점의 실제 값만으로 분야 변화 요약에 쓸 지표를 최대 두 개 고른다. */
public class NarrativeSubjectSelector {

    public List<SelectedSubject> select(List<IndicatorDisplay> indicators) {
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < indicators.size(); index++) {
            Candidate candidate = analyze(indicators.get(index), index);
            if (candidate != null && candidate.persistence() > 0) {
                candidates.add(candidate);
            }
        }

        Map<Candidate, Integer> withinUnitRanks = rankMagnitudeWithinSameUnit(candidates);
        return candidates.stream()
                .sorted(
                        Comparator.comparingInt(Candidate::persistence)
                                .reversed()
                                .thenComparingInt(withinUnitRanks::get)
                                .thenComparingInt(Candidate::registryOrder))
                .limit(2)
                .map(Candidate::toSelectedSubject)
                .toList();
    }

    private static Map<Candidate, Integer> rankMagnitudeWithinSameUnit(List<Candidate> candidates) {
        Map<Candidate, Integer> ranks = new HashMap<>();
        candidates.stream()
                .collect(java.util.stream.Collectors.groupingBy(Candidate::unit))
                .values()
                .forEach(
                        sameUnit -> {
                            List<Candidate> ordered =
                                    sameUnit.stream()
                                            .sorted(
                                                    Comparator.comparing(Candidate::magnitude)
                                                            .reversed()
                                                            .thenComparingInt(
                                                                    Candidate::registryOrder))
                                            .toList();
                            for (int index = 0; index < ordered.size(); index++) {
                                ranks.put(ordered.get(index), index);
                            }
                        });
        return ranks;
    }

    private static Candidate analyze(IndicatorDisplay indicator, int registryOrder) {
        List<PathPoint> path = indicator.path();
        if (path.size() != 3 || path.stream().anyMatch(point -> point.value() == null)) {
            return null;
        }

        int firstDirection = path.get(1).value().compareTo(path.get(0).value());
        int secondDirection = path.get(2).value().compareTo(path.get(1).value());
        int persistence;
        int direction;
        YearMonth movementMonth;
        if (firstDirection != 0
                && Integer.signum(firstDirection) == Integer.signum(secondDirection)) {
            persistence = 2;
            direction = Integer.signum(secondDirection);
            movementMonth = path.get(2).month();
        } else if (firstDirection == 0 && secondDirection != 0) {
            persistence = 1;
            direction = Integer.signum(secondDirection);
            movementMonth = path.get(2).month();
        } else if (firstDirection != 0 && secondDirection == 0) {
            persistence = 1;
            direction = Integer.signum(firstDirection);
            movementMonth = path.get(1).month();
        } else {
            return null;
        }

        BigDecimal magnitude = path.get(2).value().subtract(path.get(0).value()).abs();
        return new Candidate(
                indicator,
                direction > 0 ? Direction.UP : Direction.DOWN,
                persistence,
                movementMonth,
                magnitude,
                registryOrder,
                indicator.unit());
    }

    public enum Direction {
        UP,
        DOWN
    }

    public record SelectedSubject(
            IndicatorDisplay indicator,
            Direction direction,
            int persistence,
            YearMonth movementMonth) {}

    private record Candidate(
            IndicatorDisplay indicator,
            Direction direction,
            int persistence,
            YearMonth movementMonth,
            BigDecimal magnitude,
            int registryOrder,
            String unit) {
        SelectedSubject toSelectedSubject() {
            return new SelectedSubject(indicator, direction, persistence, movementMonth);
        }
    }
}
