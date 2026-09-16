package com.ssafy.thorp.news.infrastructure.client;

import com.ssafy.thorp.news.application.port.RssNewsSource;
import com.ssafy.thorp.news.application.result.CollectedNewsItem;
import com.ssafy.thorp.news.application.result.NewsFetchResult;
import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy.SourceKind;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * RSS 2.0 채널에서 기사 메타데이터를 읽는다(spec FR-009, research Decision 2).
 *
 * <p><b>{@code description}을 읽지 않는다.</b> 매체마다 요약이거나 본문 조각이라 v1에서는 쓰지 않기로 했다. 파서가 그 요소를 아예 건너뛰므로 본문이
 * 영속화·요약 경로로 들어올 길이 없다(spec FR-002).
 *
 * <p>RSS 2.0은 item의 거의 모든 요소가 선택이다. 누락은 정상으로 처리하고, 제목이 없는 항목만 제외한다. 제목이 없으면 사실 요약을 만들 수 없기
 * 때문이다(spec FR-003).
 *
 * <p>발행 시각은 파싱만 한다. 확인 여부 판정은 도메인의 {@code NewsArticle.classifyPublicationTime}이 한다. 여기서 시각을 지어내거나
 * 수집 시각으로 대체하지 않는다(spec FR-012).
 */
@Component
public class RssNewsClient {

    /** XML을 해석할 수 없다. 그 응답 전체를 격리한다. */
    public static final String MALFORMED_XML = "MALFORMED_XML";

    /** RFC 822 계열 형식. 두 자리 연도와 GMT 같은 관용 표기까지 받는다. */
    /**
     * 받아들이는 발행 시각 형식.
     *
     * <p>규약은 RFC 822 하나지만 실제 매체는 제각각이다. 조사해 보니 국내 경제 매체만 해도 세 갈래를 쓴다 — 표준 {@code +0900}, 콜론을 넣은
     * {@code +09:00}, 그리고 {@code dc:date}의 ISO-8601이다. 읽지 못하면 그 기사는 발행 시각 미확인으로 저장되어 과거 조회에서 통째로
     * 빠지므로, 형식 하나를 놓치는 비용이 크다.
     *
     * <p>시간대가 없는 값은 어느 형식으로도 읽지 않는다. 서버 시간대를 붙여 추정하면 그 추정이 검증된 시각처럼 저장된다(spec FR-012).
     */
    private static final List<DateTimeFormatter> DATE_FORMATS =
            List.of(
                    // 두 자리 연도를 먼저 본다. RFC_1123은 느슨해서 "26"을 서기 26년으로 읽어 버린다.
                    twoDigitYearFormat(),
                    DateTimeFormatter.RFC_1123_DATE_TIME,
                    // 콜론을 넣은 오프셋. RFC 822가 아니지만 쓰는 매체가 있다(매일경제).
                    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss XXX", Locale.ENGLISH),
                    // dc:date와 일부 매체의 pubDate가 쓰는 형식.
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
                    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
                    DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH));

    /**
     * RFC 822의 두 자리 연도를 읽는 형식.
     *
     * <p>{@code ofPattern("yy")}는 26을 서기 26년으로 읽는다. 기준 연도를 2000으로 줘야 2026년이 된다.
     */
    private static DateTimeFormatter twoDigitYearFormat() {
        return new java.time.format.DateTimeFormatterBuilder()
                .appendPattern("EEE, dd MMM ")
                .appendValueReduced(java.time.temporal.ChronoField.YEAR, 2, 2, 2000)
                .appendPattern(" HH:mm:ss Z")
                .toFormatter(Locale.ENGLISH);
    }

    private final NewsHttpClient httpClient;
    private final NewsHttpSecurityPolicy policy;
    private final Clock clock;

    public RssNewsClient(NewsHttpClient httpClient, NewsHttpSecurityPolicy policy, Clock clock) {
        this.httpClient = httpClient;
        this.policy = policy;
        this.clock = clock;
    }

    /** 한 채널을 조건부 요청으로 가져온다. */
    public NewsFetchResult fetch(RssNewsSource source) {
        java.net.URI url = policy.requireAllowedUrl(source.feedUrl(), SourceKind.RSS);
        NewsHttpResponse response =
                httpClient.get(url, SourceKind.RSS, source.etag(), source.lastModified());

        if (response.isNotModified()) {
            // 성공으로 기록하되 수집 위치와 기사 데이터는 건드리지 않는다(spec FR-013).
            return NewsFetchResult.notModified(response.etag(), response.lastModified());
        }

        policy.requireAllowedContentType(response.contentType(), SourceKind.RSS);
        ParsedChannel parsed = parse(response.body(), source);
        return NewsFetchResult.complete(
                parsed.items(), response.etag(), response.lastModified(), parsed.hints());
    }

    /** 현재 시각. 발행 시각 판정은 도메인이 하지만 수집 시각 기록은 여기서 시작한다. */
    public Instant now() {
        return clock.instant();
    }

    private ParsedChannel parse(byte[] body, RssNewsSource source) {
        XMLInputFactory factory = policy.secureXmlInputFactory();
        List<CollectedNewsItem> items = new ArrayList<>();
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(body));
            ItemBuilder current = null;
            String channelLanguage = null;
            ChannelHintBuilder hints = new ChannelHintBuilder();

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("item".equals(name)) {
                        current = new ItemBuilder();
                    } else if (current != null) {
                        readItemElement(reader, name, current);
                    } else if ("language".equals(name)) {
                        channelLanguage = textOf(reader);
                    } else {
                        hints.read(reader, name);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "item".equals(reader.getLocalName())
                        && current != null) {
                    current.build(source, channelLanguage).ifPresent(items::add);
                    current = null;
                }
            }
            return new ParsedChannel(items, hints.build());
        } catch (XMLStreamException e) {
            // 외부 엔티티가 들어 있어도 파서가 DTD를 거부하므로 여기로 온다.
            throw NewsHttpSecurityPolicy.reject(MALFORMED_XML);
        } finally {
            closeQuietly(reader);
        }
    }

    /** 한 채널을 읽은 결과. 기사와 호출 간격 힌트를 함께 돌려준다. */
    private record ParsedChannel(
            List<CollectedNewsItem> items,
            com.ssafy.thorp.news.application.result.RssChannelHints hints) {}

    /**
     * 채널의 {@code ttl}·{@code skipHours}·{@code skipDays}를 모은다(plan "수집과 재시도").
     *
     * <p>매체가 밝힌 호출 간격을 지키지 않으면 부하를 늘리고 차단당할 수 있다.
     */
    private static final class ChannelHintBuilder {
        private java.time.Duration ttl;
        private final Set<Integer> skipHours = new java.util.LinkedHashSet<>();
        private final Set<String> skipDays = new java.util.LinkedHashSet<>();

        void read(XMLStreamReader reader, String name) throws XMLStreamException {
            switch (name) {
                case "ttl" -> ttl = parseTtl(textOf(reader));
                case "hour" -> parseHour(textOf(reader)).ifPresent(skipHours::add);
                case "day" -> {
                    String day = textOf(reader);
                    if (day != null && !day.isBlank()) {
                        skipDays.add(day.trim());
                    }
                }
                default -> {
                    // 나머지 채널 요소는 쓰지 않는다.
                }
            }
        }

        com.ssafy.thorp.news.application.result.RssChannelHints build() {
            return new com.ssafy.thorp.news.application.result.RssChannelHints(
                    ttl, skipHours, skipDays);
        }

        private static java.time.Duration parseTtl(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                long minutes = Long.parseLong(raw.trim());
                return minutes > 0 ? java.time.Duration.ofMinutes(minutes) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static Optional<Integer> parseHour(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            try {
                int hour = Integer.parseInt(raw.trim());
                return hour >= 0 && hour <= 23 ? Optional.of(hour) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /** item 안에서 쓰는 요소만 읽는다. description은 여기 없다. */
    private void readItemElement(XMLStreamReader reader, String name, ItemBuilder builder)
            throws XMLStreamException {
        switch (name) {
            case "title" -> builder.title = textOf(reader);
            case "link" -> builder.link = textOf(reader);
            case "guid" -> builder.guid = textOf(reader);
            case "pubDate" -> builder.pubDate = textOf(reader);
            // <dc:date>. 네임스페이스 접두는 떼고 지역 이름으로 들어온다. pubDate를 주지 않고
            // 이것만 주는 매체가 있어(경향신문) 읽지 않으면 그 매체의 기사가 전부 시각 미확인이 된다.
            case "date" -> builder.dcDate = textOf(reader);
            case "category" -> {
                String category = textOf(reader);
                if (category != null && !category.isBlank()) {
                    builder.categories.add(category);
                }
            }
            default -> {
                // description을 포함한 나머지 요소는 읽지 않는다.
            }
        }
    }

    private static String textOf(XMLStreamReader reader) throws XMLStreamException {
        String text = reader.getElementText();
        return text == null ? null : text.trim();
    }

    /**
     * RFC 822 계열 발행 시각을 파싱한다. timezone이 없거나 해석할 수 없으면 빈 값이다.
     *
     * <p>시간대를 모르는 시각은 어느 순간인지 확정할 수 없으므로 서버 시간대를 붙여 추정하지 않는다(spec FR-012).
     */
    static Optional<Instant> parsePublishedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return Optional.of(ZonedDateTime.parse(value, format).toInstant());
            } catch (DateTimeParseException ignored) {
                // 다음 형식으로 넘어간다.
            }
        }
        return Optional.empty();
    }

    private static void closeQuietly(XMLStreamReader reader) {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (XMLStreamException ignored) {
            // 닫기 실패는 수집 결과에 영향을 주지 않는다.
        }
    }

    /** item 하나를 모으는 중간 상태. */
    private static final class ItemBuilder {
        private String title;
        private String link;
        private String guid;
        private String pubDate;

        /** {@code dc:date}. {@code pubDate}가 없을 때만 쓴다. */
        private String dcDate;

        private final List<String> categories = new ArrayList<>();

        Optional<CollectedNewsItem> build(RssNewsSource source, String channelLanguage) {
            if (title == null || title.isBlank()) {
                // 제목이 없으면 사실 요약을 만들 수 없어 표시 후보가 되지 못한다.
                return Optional.empty();
            }
            String language =
                    channelLanguage == null || channelLanguage.isBlank() ? "ko" : channelLanguage;
            return Optional.of(
                    new CollectedNewsItem(
                            blankToNull(guid),
                            blankToNull(link),
                            title,
                            language,
                            publishedAt().orElse(null),
                            null,
                            List.copyOf(categories),
                            com.ssafy.thorp.news.domain.vo.NewsSourceType.RSS));
        }

        /**
         * 발행 시각. {@code pubDate}를 먼저 보고 없으면 {@code dc:date}를 본다.
         *
         * <p>둘 다 있으면 {@code pubDate}가 이긴다. 그것이 RSS 2.0이 정한 자리이고, 두 값이 어긋나는 매체가 있을 때 어느 쪽을 믿을지 매번
         * 다르게 정하면 같은 기사가 회차마다 다른 시각으로 저장된다.
         */
        private Optional<Instant> publishedAt() {
            Optional<Instant> fromPubDate = parsePublishedAt(pubDate);
            return fromPubDate.isPresent() ? fromPubDate : parsePublishedAt(dcDate);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
