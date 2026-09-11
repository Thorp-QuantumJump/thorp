package com.ssafy.thorp.news.infrastructure.client;

import com.ssafy.thorp.news.application.port.GdeltNewsSource;
import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy.SourceKind;
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
     */
    private static final long MAX_DECOMPRESSED_BYTES = 128L * 1024 * 1024;

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
        return parse(new String(unzip(response.body()), StandardCharsets.UTF_8), queriedTopicKeys);
    }

    /**
     * 배포 파일에서 쓰는 열만 읽는다.
     *
     * <p>열은 탭으로 나뉜다. 첫 줄이 머리글이면 건너뛴다. 우리가 쓰지 않는 열은 읽지 않으므로 GDELT가 열을 늘려도 영향을 받지 않는다.
     */
    private static List<CollectedNewsItem> parse(String body, List<String> queriedTopicKeys) {
        List<CollectedNewsItem> items = new ArrayList<>();
        String[] lines = body.split("\\R");
        for (String line : lines) {
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
     * 배포 파일의 압축을 푼다.
     *
     * <p>GDELT는 zip 안에 표 파일 하나만 넣는다. 첫 항목만 읽고 나머지는 무시한다. 압축이 아닌 응답을 그대로 주는 경우도 있어, zip 서명이 아니면 받은
     * 바이트를 그대로 돌려준다.
     */
    private static byte[] unzip(byte[] body) {
        if (!looksLikeZip(body)) {
            return body;
        }
        try (java.util.zip.ZipInputStream zip =
                new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(body))) {
            if (zip.getNextEntry() == null) {
                throw NewsHttpSecurityPolicy.reject(MALFORMED_EXPORT);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = zip.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw NewsHttpSecurityPolicy.reject(MALFORMED_EXPORT);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            // 예외 메시지에는 파일 내용이 실릴 수 있다. 분류 코드만 남긴다(spec FR-021).
            throw NewsHttpSecurityPolicy.reject(MALFORMED_EXPORT);
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
