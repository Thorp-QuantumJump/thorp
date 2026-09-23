package com.ssafy.thorp.news.infrastructure.client;

import com.ssafy.thorp.news.application.port.GdeltNewsSource;
import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy.SourceKind;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * GDELT 15분 배포 파일로 필요한 구간만 보완한다(spec FR-013).
 *
 * <p>요청 구간에 걸친 15분 슬롯만 <b>오래된 순</b>으로 읽는다. 오래된 순이어야 중단된 지점부터 이어받을 때 빠진 구간이 생기지 않는다. 장기 원천 전체를 적재하지
 * 않는다.
 *
 * <p>배포 레코드에는 매체가 발행한 시각이 없다. 기록 시각만 채우고 발행 시각은 비워 둔다. 그 결과 이 경로로 들어온 기사는 과거 사례에 연결되지 않고 현재 뉴스에서도
 * 후순위에 놓인다(spec FR-006·FR-012).
 *
 * <p>제목이나 주소가 없는 레코드는 표시 후보로 만들지 않는다. 제목이 없으면 사실 요약을 만들 수 없기 때문이다(plan "수집과 재시도").
 */
@Component
public class GdeltFileNewsClient {

    /** 배포 파일을 해석할 수 없다. */
    public static final String MALFORMED_EXPORT = "MALFORMED_EXPORT";

    private static final String BASE = "https://data.gdeltproject.org/gdeltv2/";

    /** 배포 파일 이름의 꼬리. GDELT 2.0은 zip으로만 내려 준다. */
    private static final String SUFFIX = ".export.CSV.zip";

    /**
     * 압축을 풀어 읽을 최대 바이트.
     *
     * <p>응답 본문 한도는 압축된 크기에만 걸린다. 작게 압축된 파일이 풀리면서 메모리를 다 쓰는 일을 막으려면 푼 뒤의 크기도 따로 막아야 한다. 15분치 배포 파일은
     * 실제로 수십 MB를 넘지 않는다.
     *
     * <p>이 값은 상한일 뿐 할당량이 아니다. 푼 내용을 줄 단위로 흘려보내므로 한도까지 받아도 그만큼을 메모리에 쌓지 않는다.
     */
    private static final long MAX_DECOMPRESSED_BYTES = 128L * 1024 * 1024;

    /**
     * 한 줄로 읽을 최대 바이트.
     *
     * <p>줄 단위로 흘려보내도 줄바꿈이 없으면 한 줄이 파일 전체가 된다. 그러면 흘려보내는 뜻이 없어지므로 줄 길이도 따로 막는다. 배포 파일 한 행은 실제로 수
     * KB다.
     */
    private static final long MAX_LINE_BYTES = 1024L * 1024;

    /** GDELT 배포 주기. 구간을 이 크기로 잘라 읽는다. */
    private static final Duration SLOT = Duration.ofMinutes(15);

    private final NewsHttpClient httpClient;
    private final NewsHttpSecurityPolicy policy;

    public GdeltFileNewsClient(NewsHttpClient httpClient, NewsHttpSecurityPolicy policy) {
        this.httpClient = httpClient;
        this.policy = policy;
    }

    /** 요청 구간에 걸친 배포 파일을 오래된 순으로 읽어 합친다. */
    public NewsFetchResult fetch(GdeltNewsSource source) {
        List<CollectedNewsItem> items = new ArrayList<>();
        for (Instant slot = floorToSlot(source.windowStart());
                slot.isBefore(source.windowEnd());
                slot = slot.plus(SLOT)) {
            items.addAll(readSlot(slot, source.topicKeys()));
        }
        return NewsFetchResult.complete(items, null, null);
    }

    private List<CollectedNewsItem> readSlot(Instant slot, List<String> queriedTopicKeys) {
        URI url =
                policy.requireAllowedUrl(
                        BASE + GdeltTimestamps.format(slot) + SUFFIX, SourceKind.GDELT_FILE);
        NewsHttpResponse response = httpClient.get(url, SourceKind.GDELT_FILE, null, null);
        policy.requireAllowedContentType(response.contentType(), SourceKind.GDELT_FILE);
        return parse(response.body(), queriedTopicKeys);
    }

    /**
     * 배포 파일에서 쓰는 열만 읽는다.
     *
     * <p>푼 내용을 통째로 올리지 않고 한 줄씩 읽어 넘긴다. 전부 문자열로 만든 뒤 줄로 쪼개면 같은 내용이 바이트 배열·문자열·줄 배열로 여러 벌 동시에 남아, 한
     * 슬롯을 읽는 동안 실제 파일 크기의 몇 배가 메모리에 올라간다.
     *
     * <p>열은 탭으로 나뉜다. 첫 줄이 머리글이면 건너뛴다. 우리가 쓰지 않는 열은 읽지 않으므로 GDELT가 열을 늘려도 영향을 받지 않는다.
     */
    private static List<CollectedNewsItem> parse(byte[] body, List<String> queriedTopicKeys) {
        List<CollectedNewsItem> items = new ArrayList<>();
        try (InputStream source = open(body);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (columns.length < 2) {
                    continue;
                }
                if (isHeader(columns[0])) {
                    continue;
                }
                toItem(columns, queriedTopicKeys).ifPresent(items::add);
            }
        } catch (IOException e) {
            // 예외 메시지에는 파일 내용이 실릴 수 있다. 분류 코드만 남긴다(spec FR-021).
            throw NewsHttpSecurityPolicy.reject(MALFORMED_EXPORT);
        }
        return items;
    }

    private static boolean isHeader(String firstColumn) {
        return "dateadded".equalsIgnoreCase(firstColumn.trim());
    }

    private static Optional<CollectedNewsItem> toItem(
            String[] columns, List<String> queriedTopicKeys) {
        Instant recordedAt = GdeltTimestamps.parse(columns[0]).orElse(null);
        String url = blankToNull(columns[1]);
        String title = columns.length > 2 ? blankToNull(columns[2]) : null;

        if (url == null || title == null) {
            // 표시 후보가 되지 못한다. 연결·중복 판정 보조로도 제목 없이는 쓸 수 없다.
            return Optional.empty();
        }
        return Optional.of(
                new CollectedNewsItem(
                        null,
                        url,
                        title,
                        // 배포 파일에는 언어 열이 없다. 영어로 단정하지 않고 모른다고 남긴다.
                        com.ssafy.thorp.news.domain.model.NewsArticle.UNDETERMINED_LANGUAGE,
                        null,
                        recordedAt,
                        queriedTopicKeys,
                        com.ssafy.thorp.news.domain.vo.NewsSourceType.GDELT_FILE));
    }

    /**
     * 읽을 스트림을 연다.
     *
     * <p>GDELT는 zip 안에 표 파일 하나만 넣는다. 첫 항목만 읽고 나머지는 무시한다. 압축이 아닌 응답을 그대로 주는 경우도 있어, zip 서명이 아니면 받은
     * 바이트를 그대로 읽는다.
     */
    private static InputStream open(byte[] body) throws IOException {
        if (!looksLikeZip(body)) {
            return new ByteArrayInputStream(body);
        }
        java.util.zip.ZipInputStream zip =
                new java.util.zip.ZipInputStream(new ByteArrayInputStream(body));
        try {
            if (zip.getNextEntry() == null) {
                throw NewsHttpSecurityPolicy.reject(MALFORMED_EXPORT);
            }
        } catch (IOException | RuntimeException e) {
            // Inflater는 네이티브 메모리를 잡는다. 여기서 빠져나가면 돌려줄 곳이 없다.
            closeQuietly(zip);
            throw e;
        }
        return new BoundedInputStream(zip, MAX_DECOMPRESSED_BYTES);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 닫기 실패는 수집 결과에 영향을 주지 않는다.
        }
    }

    /**
     * 읽은 바이트가 한도를 넘으면 그 자리에서 끊는다. 전체 크기와 한 줄 길이를 함께 본다.
     *
     * <p>다 푼 뒤에 크기를 재면 한도가 방어가 되지 않는다. 푸는 도중에 세어야 작게 압축된 큰 파일을 막을 수 있다.
     *
     * <p>줄 길이를 여기서 세는 이유는 줄을 만드는 쪽이 이미 늦기 때문이다. 줄바꿈 없는 파일을 받으면 {@code readLine()}이 전체를 한 줄로 쌓은 뒤에야
     * 돌아온다. 줄바꿈은 UTF-8에서 이어지는 바이트로 나타나지 않으므로 바이트만 보고 세도 어긋나지 않는다.
     */
    private static final class BoundedInputStream extends FilterInputStream {

        private final long limit;
        private long total;
        private long sinceLineBreak;

        private BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1) {
                total++;
                requireWithinTotal();
                countInLine((byte) value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0) {
                total += read;
                requireWithinTotal();
                for (int i = offset; i < offset + read; i++) {
                    countInLine(buffer[i]);
                }
            }
            return read;
        }

        private void requireWithinTotal() {
            if (total > limit) {
                throw NewsHttpSecurityPolicy.reject(MALFORMED_EXPORT);
            }
        }

        /** {@code \r}만 쓰는 파일도 있어 둘 다 줄의 끝으로 본다. */
        private void countInLine(byte value) {
            if (value == '\n' || value == '\r') {
                sinceLineBreak = 0;
                return;
            }
            sinceLineBreak++;
            if (sinceLineBreak > MAX_LINE_BYTES) {
                throw NewsHttpSecurityPolicy.reject(MALFORMED_EXPORT);
            }
        }
    }

    /** zip 파일의 첫 네 바이트. */
    private static boolean looksLikeZip(byte[] body) {
        return body.length >= 4 && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4;
    }

    /** 요청 시작 시각을 그 시각이 속한 15분 슬롯의 시작으로 내린다. */
    private static Instant floorToSlot(Instant instant) {
        long slotSeconds = SLOT.toSeconds();
        long epochSecond = instant.getEpochSecond();
        return Instant.ofEpochSecond(epochSecond - Math.floorMod(epochSecond, slotSeconds));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
