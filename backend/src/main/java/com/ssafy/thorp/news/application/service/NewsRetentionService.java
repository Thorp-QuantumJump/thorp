package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.port.NewsCollectionLease;
import com.ssafy.thorp.news.application.port.NewsMetrics;
import com.ssafy.thorp.news.application.repository.NewsRetentionRepository;
import com.ssafy.thorp.news.application.result.NewsRetentionResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 보관 기한이 지나면 뉴스 도메인 자료를 일괄 삭제한다(spec FR-019·SC-007).
 *
 * <p>기한은 프로젝트 종료일(2026-10-08) 뒤 30일인 2026-11-07이다. <b>그날 전에 실행 환경과 저장소가 회수되면 삭제는 완료된 것으로 본다</b> — 이
 * 코드가 돌지 않는 것이 곧 그 경우다. 그러니 여기서 할 일은 "환경이 남아 있을 때 확실히 지우는 것" 하나다.
 *
 * <p><b>날짜만으로 판정하지 않고 시간대를 함께 본다.</b> UTC로 자르면 한국 시간 11월 7일 아침에 아직 6일이라 하루를 더 보관하게 된다. 보관 기한은 늦게
 * 틀리면 안 되는 종류의 값이다.
 *
 * <p><b>하루에 한 번 돌고, 기한 뒤에는 계속 돈다.</b> 첫 회차가 실제로 지우고 이후 회차는 0건이다. 한 번만 돌게 만들면 그날 인스턴스가 꺼져 있었을 때 영원히
 * 지워지지 않는다. 0건 회차를 실패로 보지 않는 이유도 같다.
 *
 * <p><b>점유를 잡는다.</b> 인스턴스가 여럿이면 같은 삭제가 동시에 돌아 서로의 트랜잭션을 기다린다(spec FR-022).
 */
@Service
public class NewsRetentionService {

    /** 점유 기록에 쓰는 작업 키. 수집 뉴스원과 같은 표를 쓴다(V4 주석). */
    public static final String SOURCE_KEY = "retention-cleanup";

    private final NewsRetentionRepository retention;
    private final NewsCollectionLease lease;
    private final NewsMetrics metrics;
    private final RetentionSettings settings;
    private final Clock clock;

    public NewsRetentionService(
            NewsRetentionRepository retention,
            NewsCollectionLease lease,
            NewsMetrics metrics,
            RetentionSettings settings,
            Clock clock) {
        this.retention = retention;
        this.lease = lease;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 기한이 지났으면 지운다.
     *
     * <p>트랜잭션을 여기서 열지 않는다. 점유 획득·삭제·점유 해제가 각각 짧은 트랜잭션이어야 삭제가 오래 걸려도 커넥션 하나를 계속 붙들지 않는다.
     *
     * @param owner 이 인스턴스를 가리키는 이름.
     */
    public NewsRetentionResult purgeIfDue(String owner) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, settings.zone());
        if (today.isBefore(settings.retentionDate())) {
            return NewsRetentionResult.notDue();
        }
        if (lease.tryAcquire(SOURCE_KEY, owner, now).isEmpty()) {
            return NewsRetentionResult.skipped();
        }
        try {
            Map<String, Long> deleted = retention.purgeAll();
            metrics.recordRetentionRun(deleted);
            return NewsRetentionResult.executed(deleted);
        } finally {
            // 점유 기록 자체가 방금 지워진 표에 있다. 해제는 지울 행을 찾지 못하고 조용히 끝나며,
            // 다음 회차는 점유 행을 새로 만들어 잡는다. 지우지 말고 남겨 두면 예외로 보존한 표가
            // 생기는 셈이라 SC-007이 깨진다.
            lease.release(SOURCE_KEY, owner);
        }
    }

    /**
     * 보관 기한 설정.
     *
     * <p>설정 record를 그대로 넘기지 않는다. 그것은 infrastructure에 있고 수집 한도까지 함께 들고 있다.
     *
     * @param retentionDate 이 날짜부터 삭제한다.
     * @param zone 날짜를 해석하는 시간대.
     */
    public record RetentionSettings(LocalDate retentionDate, ZoneId zone) {}
}
