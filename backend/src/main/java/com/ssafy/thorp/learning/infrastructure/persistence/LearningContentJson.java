package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.ArticleSource;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.function.Supplier;

/**
 * JSONB 컬럼({@code body_blocks}·{@code example}·{@code sources}·{@code snapshot})에 저장하는 모양과 도메인 사이
 * 변환.
 *
 * <p>도메인 모델은 Jackson에 의존하지 않으므로(ADR-0001) 저장 모양을 여기 따로 둔다. {@code sealed} 블록을 한 레코드({@link
 * BlockJson})의 {@code type}으로 펼치는 이유도 같다. 저장 키 이름은 data-model §3-6과 1:1이다.
 *
 * <p><b>이 JSON은 메시지 ObjectMapper로 직렬화된다</b>({@code MessageJsonPersistenceConfig}). null 필드는 키가
 * 생략되므로 읽는 쪽은 <b>키 없음을 null로</b> 받는다.
 *
 * <p><b>선택 값과 필수 값을 가른다.</b> 선택 값(판의 {@code example}, 출처의 {@code url})은 null 그대로 둔다. 필수 값(순서·비중·초안
 * 판 번호·본문·출처·용어 목록, 블록 종류에 따른 {@code text}/{@code termId})이 없으면 0이나 빈 목록으로 채우지 않고 {@link
 * IllegalStateException}으로 실패시킨다. 저장 경로는 필수 값을 늘 쓰므로 빠졌다면 저장 데이터가 깨진 것이고, 채워 넣으면 순서 0인 글이나 본문 없는 글을
 * 공개하게 된다(원칙 VI). 숫자를 원시 타입이 아니라 래퍼로 받는 이유도 같다. 원시 타입이면 어느 키가 빠졌는지 모르는 역직렬화 예외가 나거나, 매퍼 설정에 따라 0이
 * 된다.
 *
 * <p><b>키는 있는데 값이 도메인 규칙을 어기면</b>(형식이 틀린 식별자, 한도를 넘는 길이·비중) 도메인 생성자의 {@link
 * IllegalArgumentException}을 키 이름을 담은 {@link IllegalStateException}으로 감싼다({@link #storedValue}). 요청
 * 입력이 아니라 저장 데이터의 문제라는 것을 예외 종류로 드러내려는 것이다. 지금은 저장소 구현의 {@code @Repository} 예외 변환이 둘 다 500으로 만들지만,
 * 그 변환에 기대지 않는다.
 */
public final class LearningContentJson {

    static final String HEADING = "heading";
    static final String PARAGRAPH = "paragraph";
    static final String TERM = "term";
    static final String HYPOTHETICAL = "hypothetical";

    private LearningContentJson() {}

    /** 본문 블록. {@code term}이면 {@code termId}만, 나머지는 {@code text}만 있다. */
    public record BlockJson(String type, String text, String termId) {}

    public record SectorWeightJson(String code, Integer weightTenths) {}

    public record ExampleJson(List<SectorWeightJson> sectors) {}

    public record SourceJson(String label, String url) {}

    public record TermJson(String termId, String label, String sentence) {}

    /** 판 내용(data-model §3-6). */
    public record SnapshotJson(
            String articleId,
            String title,
            String question,
            Integer readingOrder,
            List<BlockJson> bodyBlocks,
            ExampleJson example,
            List<SourceJson> sources,
            List<TermJson> terms,
            Integer draftVersion) {}

    /**
     * 저장된 JSON에서 필수 키의 값을 꺼낸다.
     *
     * @throws IllegalStateException 키가 없거나 null일 때. 메시지에 키 이름을 담는다
     */
    static <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalStateException("stored learning JSON is missing required key: " + key);
        }
        return value;
    }

    /**
     * 저장된 값으로 도메인 객체를 만든다. 도메인 규칙 위반({@link IllegalArgumentException})은 키 이름을 담은 {@link
     * IllegalStateException}으로 바꾸고 원인을 남긴다. 키 누락({@link #required})의 {@code IllegalStateException}은
     * 그대로 통과한다.
     */
    static <T> T storedValue(String key, Supplier<T> build) {
        try {
            return build.get();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "stored learning JSON has an invalid value at key: " + key, invalid);
        }
    }

    static List<BlockJson> blocksToJson(List<ArticleBlock> blocks) {
        return blocks.stream().map(LearningContentJson::blockToJson).toList();
    }

    static List<ArticleBlock> blocksFromJson(List<BlockJson> blocks) {
        return required(blocks, "bodyBlocks").stream()
                .map(block -> storedValue("bodyBlocks", () -> blockFromJson(block)))
                .toList();
    }

    static ExampleJson exampleToJson(ExampleAllocation example) {
        if (example == null) {
            return null;
        }
        return new ExampleJson(
                example.sectors().stream()
                        .map(sector -> new SectorWeightJson(sector.code(), sector.weightTenths()))
                        .toList());
    }

    /** 예시 자체는 선택 값이다. 있으면 섹터 목록과 각 섹터의 코드·비중은 필수다. */
    static ExampleAllocation exampleFromJson(ExampleJson example) {
        if (example == null) {
            return null;
        }
        return storedValue(
                "example",
                () ->
                        new ExampleAllocation(
                                required(example.sectors(), "example.sectors").stream()
                                        .map(
                                                sector ->
                                                        new ExampleAllocation.SectorWeight(
                                                                required(
                                                                        sector.code(),
                                                                        "example.sectors[].code"),
                                                                required(
                                                                        sector.weightTenths(),
                                                                        "example.sectors[].weightTenths")))
                                        .toList()));
    }

    static List<SourceJson> sourcesToJson(List<ArticleSource> sources) {
        return sources.stream()
                .map(source -> new SourceJson(source.label(), source.url()))
                .toList();
    }

    static List<ArticleSource> sourcesFromJson(List<SourceJson> sources) {
        return required(sources, "sources").stream()
                .map(
                        source ->
                                new ArticleSource(
                                        required(source.label(), "sources[].label"), source.url()))
                .toList();
    }

    static SnapshotJson snapshotToJson(ArticleSnapshot snapshot) {
        return new SnapshotJson(
                snapshot.articleId().value(),
                snapshot.title(),
                snapshot.question(),
                snapshot.readingOrder(),
                blocksToJson(snapshot.bodyBlocks()),
                exampleToJson(snapshot.example()),
                sourcesToJson(snapshot.sources()),
                snapshot.terms().stream()
                        .map(
                                term ->
                                        new TermJson(
                                                term.termId().value(),
                                                term.label(),
                                                term.sentence()))
                        .toList(),
                snapshot.draftVersion());
    }

    static ArticleSnapshot snapshotFromJson(SnapshotJson snapshot) {
        return new ArticleSnapshot(
                storedValue(
                        "articleId",
                        () -> new ArticleId(required(snapshot.articleId(), "articleId"))),
                required(snapshot.title(), "title"),
                required(snapshot.question(), "question"),
                required(snapshot.readingOrder(), "readingOrder"),
                blocksFromJson(snapshot.bodyBlocks()),
                exampleFromJson(snapshot.example()),
                sourcesFromJson(snapshot.sources()),
                required(snapshot.terms(), "terms").stream()
                        .map(
                                term ->
                                        new LearningTerm(
                                                storedValue(
                                                        "terms[].termId",
                                                        () ->
                                                                new TermId(
                                                                        required(
                                                                                term.termId(),
                                                                                "terms[].termId"))),
                                                required(term.label(), "terms[].label"),
                                                required(term.sentence(), "terms[].sentence")))
                        .toList(),
                required(snapshot.draftVersion(), "draftVersion"));
    }

    private static BlockJson blockToJson(ArticleBlock block) {
        return switch (block) {
            case HeadingBlock heading -> new BlockJson(HEADING, heading.text(), null);
            case ParagraphBlock paragraph -> new BlockJson(PARAGRAPH, paragraph.text(), null);
            case TermBlock term -> new BlockJson(TERM, null, term.termId().value());
            case HypotheticalBlock hypothetical ->
                    new BlockJson(HYPOTHETICAL, hypothetical.text(), null);
        };
    }

    /**
     * 저장된 블록을 읽는다. 알 수 없는 종류는 건너뛰지 않고 실패시킨다. 저장 경로가 네 종류만 쓰므로 모르는 값이 있다는 것은 저장 데이터가 깨졌다는 뜻이고, 조용히
     * 버리면 공개 글에서 문단이 사라진 채 나간다(원칙 VI).
     */
    private static ArticleBlock blockFromJson(BlockJson block) {
        String type = required(block.type(), "bodyBlocks[].type");
        return switch (type) {
            case HEADING -> new HeadingBlock(required(block.text(), "bodyBlocks[].text"));
            case PARAGRAPH -> new ParagraphBlock(required(block.text(), "bodyBlocks[].text"));
            case TERM -> new TermBlock(new TermId(required(block.termId(), "bodyBlocks[].termId")));
            case HYPOTHETICAL -> new HypotheticalBlock(required(block.text(), "bodyBlocks[].text"));
            default -> throw new IllegalStateException("unknown stored block type: " + type);
        };
    }
}
