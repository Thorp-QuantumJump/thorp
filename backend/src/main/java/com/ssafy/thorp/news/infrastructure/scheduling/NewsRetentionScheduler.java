package com.ssafy.thorp.news.infrastructure.scheduling;

import com.ssafy.thorp.news.application.result.NewsRetentionResult;
import com.ssafy.thorp.news.application.service.NewsRetentionService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 날마다 한 번 보관 기한을 확인하고, 지났으면 뉴스 자료를 지운다(spec FR-019).
 *
 * <p><b>기한 전에도 돈다.</b> 도는 것 자체가 확인이고, 기한 전에는 아무것도 하지 않는다. 기한 당일에만 한 번 돌게 만들면 그날 인스턴스가 꺼져 있었을 때 영원히
 * 지워지지 않는다.
 *
 * <p>여기서 판정을 하지 않는다. 기한·시간대·점유는 모두 서비스의 몫이고 이 클래스는 언제 부를지만 안다.
 */
@Component
public class NewsRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsRetentionScheduler.class);

    private final NewsRetentionService retention;

    /** 이 인스턴스를 가리키는 이름. 점유 주인으로 기록한다. */
    private final String instanceId = UUID.randomUUID().toString();

    public NewsRetentionScheduler(NewsRetentionService retention) {
        this.retention = retention;
    }

    /** 새벽에 돌린다. 조회가 가장 적은 시간대라 긴 삭제 트랜잭션이 사용자 요청과 겹치지 않는다. */
    @Scheduled(
            cron = "${thorp.news.retention-cron:0 30 3 * * *}",
            zone = "${thorp.news.retention-zone:Asia/Seoul}")
    public void purgeIfDue() {
        try {
            NewsRetentionResult result = retention.purgeIfDue(instanceId);
            if (result.outcome() == NewsRetentionResult.Outcome.EXECUTED) {
                log.info(
                        "뉴스 보관 기한 일괄 삭제를 실행했다 deletedRows={} tables={}",
                        result.totalDeleted(),
                        result.deletedRows().size());
            }
        } catch (RuntimeException e) {
            // 다음 날 회차가 다시 시도한다. 여기서 터뜨리면 스케줄러 스레드가 이 작업을 놓는다.
            log.warn("뉴스 보관 기한 일괄 삭제가 실패했다");
        }
    }
}
