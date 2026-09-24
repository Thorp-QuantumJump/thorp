package com.ssafy.thorp.sector.infrastructure.persistence;

import com.ssafy.thorp.sector.application.repository.IndexSeriesRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link IndexSeriesRepository}의 구현. {@code daily_index}에는 엔티티 매핑이 없어 {@link
 * PriceSeriesRepositoryImpl}처럼 SQL로 읽는다.
 */
@Repository
public class IndexSeriesRepositoryImpl implements IndexSeriesRepository {

    private static final String CLOSES_SQL =
            """
            SELECT date, close
              FROM daily_index
             WHERE dataset_id = :datasetId
               AND index_code = :indexCode
               AND date BETWEEN :from AND :to
             ORDER BY date ASC
            """;

    @PersistenceContext private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<IndexClose> findCloses(
            String datasetId, String indexCode, LocalDate from, LocalDate to) {
        List<?> rows =
                entityManager
                        .createNativeQuery(CLOSES_SQL)
                        .setParameter("datasetId", datasetId)
                        .setParameter("indexCode", indexCode)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getResultList();
        return rows.stream()
                .map(row -> (Object[]) row)
                .map(row -> new IndexClose(toLocalDate(row[0]), (BigDecimal) row[1]))
                .toList();
    }

    /** 드라이버가 {@link java.sql.Date}로 줄 때와 {@link LocalDate}로 줄 때를 함께 받는다. */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) return date;
        return ((java.sql.Date) value).toLocalDate();
    }
}
