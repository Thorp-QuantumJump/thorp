package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.ReleaseLogRepository;
import com.ssafy.thorp.learning.domain.model.ReleaseLogEntry;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ReleaseLogRepositoryImpl implements ReleaseLogRepository {

    private final LearningReleaseLogJpaRepository logJpaRepository;
    private final LearningArticleJpaRepository articleJpaRepository;
    private final StageDirectory stageDirectory;

    public ReleaseLogRepositoryImpl(
            LearningReleaseLogJpaRepository logJpaRepository,
            LearningArticleJpaRepository articleJpaRepository,
            StageDirectory stageDirectory) {
        this.logJpaRepository = logJpaRepository;
        this.articleJpaRepository = articleJpaRepository;
        this.stageDirectory = stageDirectory;
    }

    /** 바로 flush한다. 같은 {@code entryKey}의 제약 위반이 트랜잭션 끝이 아니라 이 자리에서 드러나게 한다. */
    @Override
    public void append(ReleaseLogEntry entry) {
        logJpaRepository.saveAndFlush(
                ReleaseLogMapper.toEntity(
                        entry,
                        stageDirectory.lookup().idOf(entry.stageKey()),
                        entry.articleId() == null
                                ? null
                                : PersistenceLookups.requireArticleId(
                                        articleJpaRepository, entry.articleId())));
    }

    /** 단계 키와 글 키를 조인으로 함께 읽는다. 줄마다 따로 읽지 않는다. */
    @Override
    public List<ReleaseLogEntry> findAllLatestFirst() {
        return logJpaRepository.findAllKeyedLatestFirst().stream()
                .map(ReleaseLogMapper::toDomain)
                .toList();
    }
}
