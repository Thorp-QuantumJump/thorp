package com.ssafy.thorp.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.support.MigrationFiles;
import com.ssafy.thorp.support.MigrationFiles.MigrationFile;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 마이그레이션 파일명 규칙을 검사한다. <b>DB도 Docker도 쓰지 않는다.</b>
 *
 * <p>이 테스트가 따로 있는 이유는 검사 시점 때문이다. 스키마를 실제로 적용해 보는 {@code FlywayMigrationTest}·{@code
 * ParamSearchMigrationTest}는 {@code PostgresTestSupport}를 상속하므로 Docker가 없으면 <b>실패가 아니라 조용한 스킵</b>이
 * 되고 빌드는 통과한다. 버전 중복처럼 파일명만 봐도 알 수 있는 문제까지 컨테이너에 맡기면, 컨테이너가 없는 환경에서 검증이 통째로 비어 버린다.
 *
 * <p>실제로 2026-09-11에 서로 다른 두 MR이 각각 {@code V3}을 추가한 채 병합되어 {@code Found more than one migration
 * with version 3}으로 기동이 막혔다. 아래 {@link #versionsAreUnique()}가 그 상태를 Docker 없이 잡는다.
 */
class MigrationFileConventionTest {

    @Test
    @DisplayName("마이그레이션 파일을 하나 이상 찾는다")
    void migrationFilesAreFound() {
        // 클래스패스 열거가 빈 목록을 돌려주면 아래 단언들이 모두 공허하게 통과한다. 그 상태를 먼저 막는다.
        assertThat(MigrationFiles.rawFileNames())
                .as("classpath:db/migration 에서 마이그레이션을 찾지 못했다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("모든 마이그레이션 파일명이 규칙을 지킨다")
    void fileNamesFollowNamingRule() {
        List<String> violations =
                MigrationFiles.rawFileNames().stream()
                        .filter(name -> !MigrationFiles.matchesNamingRule(name))
                        .toList();

        assertThat(violations).as("파일명 규칙: %s", MigrationFiles.namingRuleDescription()).isEmpty();
    }

    @Test
    @DisplayName("마이그레이션 버전이 중복되지 않는다")
    void versionsAreUnique() {
        Map<String, List<String>> filesByVersion =
                MigrationFiles.all().stream()
                        .collect(
                                Collectors.groupingBy(
                                        MigrationFile::version,
                                        Collectors.mapping(
                                                MigrationFile::fileName, Collectors.toList())));

        Map<String, List<String>> duplicated =
                filesByVersion.entrySet().stream()
                        .filter(entry -> entry.getValue().size() > 1)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(duplicated)
                .as(
                        "같은 버전을 가진 마이그레이션이 둘 이상이면 Flyway가 파일을 해석하는 단계에서 기동을 멈춘다."
                                + " 아직 운영에 적용되지 않은 쪽의 버전을 올려 해소한다")
                .isEmpty();
    }
}
