package com.ssafy.thorp.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 클래스패스의 Flyway 마이그레이션 파일 목록을 읽는다.
 *
 * <p>테스트가 마이그레이션 <b>버전 번호를 직접 적지 않게 하려고</b> 둔다. 예전에는 각 테스트가 {@code WHERE version = '4'}처럼 번호를 문자열로
 * 박고 있어서, 버전을 하나 바꾸면(V3 중복을 풀며 뉴스 마이그레이션을 V4로 개명한 일이 그랬다) 테스트까지 함께 고쳐야 했다. 여기서 파일 목록을 읽어오면 단언이 번호와
 * 무관해지고, 뒤에 버전 체계를 timestamp로 바꾸더라도 테스트는 그대로 둔다.
 *
 * <p>DB도 Docker도 필요 없다. 클래스패스만 읽으므로 컨테이너가 없는 환경에서도 그대로 돈다.
 */
public final class MigrationFiles {

    /** 운영과 같은 위치다. {@code application-prod.yml}의 {@code spring.flyway.locations}와 맞춘다. */
    private static final String LOCATION_PATTERN = "classpath*:db/migration/*.sql";

    /**
     * {@code V<버전>__<설명>.sql}. 버전은 숫자만 받는다.
     *
     * <p>점 표기(`V1.1`)를 받지 않는 이유: 순차 번호와 timestamp가 섞인 상태에서 점까지 허용하면 어느 쪽이 먼저인지 사람이 읽기 어렵고, 아래
     * {@link #versionOrder()}의 비교도 파트별 비교로 복잡해진다. 팀에 점 버전이 필요한 사례가 아직 없다.
     */
    private static final Pattern FILE_NAME =
            Pattern.compile("^V(?<version>\\d+)__(?<description>[a-z0-9_]+)\\.sql$");

    private MigrationFiles() {}

    /**
     * 마이그레이션 파일 하나. {@code version}은 파일명에 적힌 문자열 그대로이며 {@code flyway_schema_history.version}과 같은
     * 값이다.
     */
    public record MigrationFile(String fileName, String version, String description) {}

    /** 클래스패스에 있는 마이그레이션 파일 전부. 버전 오름차순이다. */
    public static List<MigrationFile> all() {
        return rawFileNames().stream().map(MigrationFiles::parse).sorted(versionOrder()).toList();
    }

    /** 마이그레이션 버전 목록. 버전 오름차순이다. */
    public static List<String> versions() {
        return all().stream().map(MigrationFile::version).toList();
    }

    /**
     * 파일명을 해석하지 않고 그대로 돌려준다.
     *
     * <p>{@link #all()}은 규칙에 맞지 않는 파일을 만나면 예외를 던지므로, "규칙 위반을 찾는" 테스트는 이쪽을 써야 한다.
     */
    public static List<String> rawFileNames() {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver().getResources(LOCATION_PATTERN);
            return Arrays.stream(resources)
                    .map(Resource::getFilename)
                    .filter(name -> name != null)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("마이그레이션 파일 목록을 읽지 못했다: " + LOCATION_PATTERN, e);
        }
    }

    /** 마이그레이션 한 파일의 SQL 원문. 테스트가 SQL 문장을 베껴 적지 않게 한다. */
    public static String read(String fileName) {
        Resource resource =
                new PathMatchingResourcePatternResolver()
                        .getResource("classpath:db/migration/" + fileName);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("마이그레이션 파일을 읽지 못했다: " + fileName, e);
        }
    }

    /** 파일명이 규칙에 맞는지 본다. 규칙은 {@link #FILE_NAME} 주석 참고. */
    public static boolean matchesNamingRule(String fileName) {
        return FILE_NAME.matcher(fileName).matches();
    }

    /** 사람이 읽을 파일명 규칙. 단언 실패 메시지에 쓴다. */
    public static String namingRuleDescription() {
        return "V<숫자 버전>__<소문자·숫자·밑줄 설명>.sql";
    }

    /**
     * 버전 비교 순서.
     *
     * <p><b>문자열이 아니라 수로 비교한다.</b> 순차 번호와 timestamp가 섞이면 사전식 비교는 {@code "20260911103045" < "4"}로
     * 뒤집힌다. Flyway도 수로 비교하므로 여기서도 같게 맞춘다.
     */
    public static Comparator<MigrationFile> versionOrder() {
        return Comparator.comparing(file -> new BigInteger(file.version()));
    }

    private static MigrationFile parse(String fileName) {
        Matcher matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "마이그레이션 파일명이 규칙에 맞지 않는다: "
                            + fileName
                            + " (규칙: "
                            + namingRuleDescription()
                            + ")");
        }
        return new MigrationFile(fileName, matcher.group("version"), matcher.group("description"));
    }
}
