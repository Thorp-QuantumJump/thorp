package com.ssafy.thorp.regimelink.infrastructure.persistence;

import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 계산 저장소 구현.
 *
 * <p>외부 식별자({@code RL-…})는 PK 가 나온 뒤에 짓는다 — 날짜와 순번으로 만들기 때문이다. 004 {@code BacktestId} 와 같은 방식이다.
 */
@Repository
public class RegimeLinkRepositoryImpl implements RegimeLinkRepository {

    private final RegimeLinkJpaRepository jpaRepository;
    private final Clock clock;

    public RegimeLinkRepositoryImpl(RegimeLinkJpaRepository jpaRepository, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.clock = clock;
    }

    @Override
    public Optional<RegimeLink> findByLinkId(LinkId linkId) {
        return jpaRepository.findByLinkId(linkId.value()).map(RegimeLinkMapper::toDomain);
    }

    @Override
    public Optional<RegimeLink> findUnit(Long runId, CaseRef caseRef, Scenario scenario) {
        return jpaRepository
                .findByRunIdAndSnapshotKeyAndEndMonthAndScenario(
                        runId, caseRef.snapshotKey(), caseRef.endMonth().atDay(1), scenario.name())
                .map(RegimeLinkMapper::toDomain);
    }

    @Override
    public List<RegimeLink> findByRunId(Long runId) {
        return jpaRepository.findByRunId(runId).stream().map(RegimeLinkMapper::toDomain).toList();
    }

    @Override
    public RegimeLink save(RegimeLink link) {
        return RegimeLinkMapper.toDomain(
                jpaRepository.saveAndFlush(RegimeLinkMapper.toEntity(link, LocalDate.now(clock))));
    }
}
