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

/**
 * JSONB 컬럼({@code body_blocks}·{@code example}·{@code sources}·{@code snapshot})에 저장하는 모양과 도메인 사이
 * 변환.
 *
 * <p>도메인 모델은 Jackson에 의존하지 않으므로(ADR-0001) 저장 모양을 여기 따로 둔다. {@code sealed} 블록을 한 레코드({@link
 * BlockJson})의 {@code type}으로 펼치는 이유도 같다. 저장 키 이름은 data-model §3-6과 1:1이다.
 *
 * <p><b>이 JSON은 메시지 ObjectMapper로 직렬화된다</b>({@code MessageJsonPersistenceConfig}). null 필드는 키가
 * 생략되므로 읽는 쪽은 <b>키 없음을 null로</b> 받는다. {@code heading} 블록의 {@code termId}, 출처의 {@code url}이 그렇다.
 */
public final class LearningContentJson {

    static final String HEADING = "heading";
    static final String PARAGRAPH = "paragraph";
    static final String TERM = "term";
    static final String HYPOTHETICAL = "hypothetical";

    private LearningContentJson() {}

    /** 본문 블록. {@code term}이면 {@code termId}만, 나머지는 {@code text}만 있다. */
    public record BlockJson(String type, String text, String termId) {}

    public record SectorWeightJson(String code, int weightTenths) {}

    public record ExampleJson(List<SectorWeightJson> sectors) {}

    public record SourceJson(String label, String url) {}

    public record TermJson(String termId, String label, String sentence) {}

    /** 판 내용(data-model §3-6). */
    public record SnapshotJson(
            String articleId,
            String title,
            String question,
            int readingOrder,
            List<BlockJson> bodyBlocks,
            ExampleJson example,
            List<SourceJson> sources,
            List<TermJson> terms,
            int draftVersion) {}

    static List<BlockJson> blocksToJson(List<ArticleBlock> blocks) {
        return blocks.stream().map(LearningContentJson::blockToJson).toList();
    }

    static List<ArticleBlock> blocksFromJson(List<BlockJson> blocks) {
        return blocks == null
                ? List.of()
                : blocks.stream().map(LearningContentJson::blockFromJson).toList();
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

    static ExampleAllocation exampleFromJson(ExampleJson example) {
        if (example == null) {
            return null;
        }
        return new ExampleAllocation(
                example.sectors().stream()
                        .map(
                                sector ->
                                        new ExampleAllocation.SectorWeight(
                                                sector.code(), sector.weightTenths()))
                        .toList());
    }

    static List<SourceJson> sourcesToJson(List<ArticleSource> sources) {
        return sources.stream()
                .map(source -> new SourceJson(source.label(), source.url()))
                .toList();
    }

    static List<ArticleSource> sourcesFromJson(List<SourceJson> sources) {
        return sources == null
                ? List.of()
                : sources.stream()
                        .map(source -> new ArticleSource(source.label(), source.url()))
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
                new ArticleId(snapshot.articleId()),
                snapshot.title(),
                snapshot.question(),
                snapshot.readingOrder(),
                blocksFromJson(snapshot.bodyBlocks()),
                exampleFromJson(snapshot.example()),
                sourcesFromJson(snapshot.sources()),
                snapshot.terms() == null
                        ? List.of()
                        : snapshot.terms().stream()
                                .map(
                                        term ->
                                                new LearningTerm(
                                                        new TermId(term.termId()),
                                                        term.label(),
                                                        term.sentence()))
                                .toList(),
                snapshot.draftVersion());
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
        String type = block.type() == null ? "" : block.type();
        return switch (type) {
            case HEADING -> new HeadingBlock(block.text());
            case PARAGRAPH -> new ParagraphBlock(block.text());
            case TERM -> new TermBlock(new TermId(block.termId()));
            case HYPOTHETICAL -> new HypotheticalBlock(block.text());
            default -> throw new IllegalStateException("unknown stored block type: " + type);
        };
    }
}
