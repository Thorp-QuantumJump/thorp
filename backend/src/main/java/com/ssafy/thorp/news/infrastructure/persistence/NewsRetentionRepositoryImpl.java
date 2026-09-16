package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.application.repository.NewsRetentionRepository;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NewsRetentionRepository} 구현(spec FR-019·SC-007).
 *
 * <p><b>cascade에 맡기지 않고 표마다 지운다.</b> 외래 키에 {@code ON DELETE CASCADE}가 걸려 있어 기사만 지워도 연결과 묶음은 따라
 * 사라지지만, 그러면 무엇이 몇 건 지워졌는지 셀 수 없다. 삭제 결과를 지표로 남겨야 하는데(spec FR-023) 셀 수 없으면 "지웠다"는 말을 확인할 방법이 없다.
 *
 * <p>순서는 자식부터다. cascade가 있어도 부모를 먼저 지우면 자식 건수가 0으로 읽혀 셈이 어긋난다.
 *
 * <p><b>한 트랜잭션이다.</b> 중간에 끊기면 일부 표만 빈 채로 남고, 다음 회차가 왔을 때 이미 지운 것인지 아닌지 알 수 없다.
 *
 * <p>JPQL이 아니라 네이티브 질의를 쓴다. 연결 표({@code news_article_topic} 등)는 엔티티가 아니고, 영속성 컨텍스트를 거치지 않는 편이 수십만
 * 행을 지울 때 메모리를 쓰지 않는다.
 */
@Repository
public class NewsRetentionRepositoryImpl implements NewsRetentionRepository {

    /**
     * 지우는 순서. 자식 → 부모다.
     *
     * <p>목록을 여기 한 곳에 둔다. 뉴스 표를 새로 만들면서 여기 더하지 않으면 그 표만 남는다 — {@code NewsRetentionIT}가 스키마의 {@code
     * news_}로 시작하는 표를 전부 세어 이 목록과 대조한다.
     */
    static final List<String> TABLES =
            List.of(
                    "news_article_topic",
                    "news_article_sector",
                    "news_digest_article",
                    "news_digest",
                    "news_article",
                    "news_topic",
                    "news_publisher_permission_history",
                    "news_publisher",
                    "news_rule_version",
                    "news_collection_state");

    private final EntityManager entityManager;

    public NewsRetentionRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public Map<String, Long> purgeAll() {
        Map<String, Long> deleted = new LinkedHashMap<>();
        for (String table : TABLES) {
            // 표 이름은 위 상수 목록에서만 온다. 바깥 값이 섞일 자리가 없다.
            int rows = entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            deleted.put(table, (long) rows);
        }
        // 지운 뒤 남아 있는 영속 객체를 들고 있으면 이어지는 조회가 사라진 행을 되살린다.
        entityManager.clear();
        return deleted;
    }
}
