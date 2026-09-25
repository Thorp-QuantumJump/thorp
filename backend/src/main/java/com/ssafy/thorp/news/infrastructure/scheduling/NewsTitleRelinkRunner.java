package com.ssafy.thorp.news.infrastructure.scheduling;

import com.ssafy.thorp.news.application.service.NewsTitleRelinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동이 끝나면 저장된 기사를 한 번 제목 키워드로 다시 연결한다(spec FR-010).
 *
 * <p>수집과 같은 스위치({@code thorp.news.collection-enabled})를 따른다. 저장소에 쓰는 일이라 수집을 끈 환경에서는 돌지 않는다.
 *
 * <p><b>실패해도 기동을 막지 않는다.</b> 다시 연결은 더하기만 해서 다음 기동에 다시 돌면 되고, 그 사이 새 수집은 v2 규칙으로 연결된다.
 */
@Component
@ConditionalOnProperty(
        name = "thorp.news.collection-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NewsTitleRelinkRunner {

    private static final Logger log = LoggerFactory.getLogger(NewsTitleRelinkRunner.class);

    private final NewsTitleRelinkService relink;

    public NewsTitleRelinkRunner(NewsTitleRelinkService relink) {
        this.relink = relink;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void relinkOnStartup() {
        try {
            int relinked = relink.relinkStoredTitles();
            log.info("저장된 기사를 제목 키워드로 다시 연결했다 articles={}", relinked);
        } catch (RuntimeException e) {
            log.warn("제목 키워드 다시 연결에 실패했다. 다음 기동에 다시 시도한다", e);
        }
    }
}
