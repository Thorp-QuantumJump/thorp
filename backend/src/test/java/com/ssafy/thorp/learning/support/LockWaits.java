package com.ssafy.thorp.learning.support;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * 커밋하지 않은 잠금으로 요청을 세워 두는 동시성 테스트의 도우미.
 *
 * <p>테스트는 풀과 별개인 연결({@link #rawConnection})로 행을 잠그고, 요청을 다른 스레드에서 보낸 뒤 그 요청이 잠금에 막혔을 때({@link
 * #awaitBlockedBy}) 커밋하거나 되돌린다.
 */
public final class LockWaits {

    private LockWaits() {}

    /** 테스트 연결 풀과 별개인 연결. 풀의 연결은 요청이 써야 한다. */
    public static Connection rawConnection(DataSource dataSource) throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        return DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }

    /** 요청 하나가 {@code holder}의 잠금에 막혀 기다릴 때까지 기다린다. */
    public static void awaitBlockedBy(Connection holder, Connection watcher) throws Exception {
        awaitBlockedBy(holder, watcher, 1);
    }

    /**
     * {@code holder} 뒤에 줄 선 세션이 {@code sessions}개가 될 때까지 기다린다. 그 뒤에 커밋해야 요청이 앞선 검사를 이미 통과한 채 잠금이나
     * 제약 앞에 서 있는 경로를 실제로 탄다.
     *
     * <p>DB 전체의 잠금 대기를 세면 안 된다. 같은 컨테이너를 쓰는 다른 세션(스케줄러의 advisory lock 등)이 기다리는 순간 곧바로 넘어가, 요청이 잠금
     * 앞에 서기 전에 커밋하게 된다. 그래서 {@code holder}에서 시작하는 대기 사슬만 센다({@code pg_blocking_pids}). 같은 행을 기다리는
     * 둘째 요청은 holder가 아니라 첫째 요청의 잠금을 기다리므로 사슬 전체를 센다.
     */
    public static void awaitBlockedBy(Connection holder, Connection watcher, int sessions)
            throws Exception {
        int holderPid;
        try (ResultSet pid = holder.createStatement().executeQuery("SELECT pg_backend_pid()")) {
            pid.next();
            holderPid = pid.getInt(1);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try (PreparedStatement blocked =
                    watcher.prepareStatement(
                            """
                            WITH RECURSIVE chain (pid) AS (
                                SELECT pid FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))
                                UNION
                                SELECT a.pid FROM pg_stat_activity a JOIN chain c
                                  ON c.pid = ANY (pg_blocking_pids(a.pid))
                            )
                            SELECT count(*) FROM chain
                            """)) {
                blocked.setInt(1, holderPid);
                try (ResultSet rows = blocked.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= sessions) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("requests never waited on the lock held by pid " + holderPid);
    }
}
