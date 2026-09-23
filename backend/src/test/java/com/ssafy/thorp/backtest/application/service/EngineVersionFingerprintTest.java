package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재현 키의 실행환경 지문이 지금 워커 lock 과 같은지(spec 004 T028).
 *
 * <p>워커 {@code tests/test_dependency_pins.py}에도 같은 검사가 있다. CI 는 바뀐 파트의 테스트만 돌리므로, 백엔드 설정만 고친 MR 에서는
 * 워커 쪽 검사가 돌지 않는다. {@code engine-version}을 올리다 지문을 빠뜨리면 여기서 잡는다.
 */
class EngineVersionFingerprintTest {

    private static final Path WORKER_LOCK = Path.of("..", "worker", "uv.lock");
    private static final Path CONFIG = Path.of("src", "main", "resources", "application.yml");
    private static final Pattern ENGINE_VERSION =
            Pattern.compile("^\\s*engine-version:\\s*\"([^\"]*)\"", Pattern.MULTILINE);

    @Test
    @DisplayName("engine-version 은 worker/uv.lock(LF) sha256 앞 12자를 지문으로 끝에 단다")
    void engineVersionEndsWithTheLockFingerprint() throws IOException, NoSuchAlgorithmException {
        String lock = Files.readString(WORKER_LOCK, StandardCharsets.UTF_8).replace("\r\n", "\n");
        byte[] digest =
                MessageDigest.getInstance("SHA-256").digest(lock.getBytes(StandardCharsets.UTF_8));
        String expected = "uv.lock " + HexFormat.of().formatHex(digest).substring(0, 12);

        Matcher matcher = ENGINE_VERSION.matcher(Files.readString(CONFIG, StandardCharsets.UTF_8));

        assertThat(matcher.find()).as("%s 에서 engine-version 을 찾지 못했다", CONFIG).isTrue();
        assertThat(matcher.group(1))
                .as("engine-version 의 지문이 uv.lock 과 다르다. 끝을 '%s' 로 바꾼다", expected)
                .endsWith(" / " + expected);
    }
}
