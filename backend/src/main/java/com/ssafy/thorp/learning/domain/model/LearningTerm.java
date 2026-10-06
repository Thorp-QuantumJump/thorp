package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 용어 설명 하나(data-model §7-1). 초안 작업본에서도, 판의 내용에서도 같은 모양이다.
 *
 * <p>형식마다 쓰는 필드가 다르다. V1 용어는 설명 문장({@code sentence}) 하나이고, V2 용어는 다른 이름과 뜻·읽는 법·한계 세 문장이다. 쓰지 않는
 * 필드는 빈 값이다. 어느 형식의 모양인지는 {@link #fitsV1()}·{@link #fitsV2()}가 가른다.
 *
 * <p>초안에서는 문장이 빈 문자열일 수 있다. 비었는지는 게시 전 검사({@code TERM_SENTENCE}·{@code TERM_SENTENCES})가 가른다.
 *
 * <p><b>넘겨받기</b>(data-model §7-2, research R24): V2 초안의 용어가 다른 글이 소유한 {@code termId}를 쓰려면 {@code
 * transferFrom}에 지금 소유 글을 적는다. 넘기기는 이 글이 게시될 때 하고, 그때 {@code transferFrom}이 비워진다. 판의 용어에는 이 값이 없다.
 *
 * @param sentence V1 설명 문장. V2 용어는 빈 문자열이다
 * @param aliases 다른 이름(통용어·약어·영어 이름) 0~4개, 각 1~40자, 중복 없음(FR-006)
 * @param meaning 뜻 문장. 도움말 창에 보이는 문장이다(FR-008)
 * @param reading 읽는 법 문장
 * @param caveat 한계 문장
 * @param transferFrom 넘겨받는 중이면 지금 소유 글. V1 용어와 판의 용어는 늘 null이다
 */
public record LearningTerm(
        TermId termId,
        String label,
        String sentence,
        List<String> aliases,
        String meaning,
        String reading,
        String caveat,
        ArticleId transferFrom) {

    public static final int LABEL_MAX_LENGTH = 50;
    public static final int SENTENCE_MAX_LENGTH = 300;
    public static final int MAX_ALIASES = 4;
    public static final int ALIAS_MAX_LENGTH = 40;
    public static final int V2_SENTENCE_MAX_LENGTH = 200;

    public LearningTerm {
        Objects.requireNonNull(termId, "termId must not be null");
        Objects.requireNonNull(label, "term label must not be null");
        Objects.requireNonNull(sentence, "term sentence must not be null");
        Objects.requireNonNull(meaning, "term meaning must not be null");
        Objects.requireNonNull(reading, "term reading must not be null");
        Objects.requireNonNull(caveat, "term caveat must not be null");
        aliases = List.copyOf(aliases);
        if (aliases.size() > MAX_ALIASES) {
            throw new IllegalArgumentException(
                    "a term must have at most " + MAX_ALIASES + " aliases: " + aliases.size());
        }
        Set<String> seen = new HashSet<>();
        for (String alias : aliases) {
            if (alias.isEmpty() || alias.length() > ALIAS_MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "term alias must be 1.." + ALIAS_MAX_LENGTH + " characters");
            }
            if (!seen.add(alias)) {
                throw new IllegalArgumentException("duplicate term alias: " + alias);
            }
        }
        for (String text : List.of(meaning, reading, caveat)) {
            if (text.length() > V2_SENTENCE_MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "term meaning·reading·caveat must be at most "
                                + V2_SENTENCE_MAX_LENGTH
                                + " characters");
            }
        }
    }

    /** 넘겨받지 않는 용어. 판의 용어와 V1 용어가 이 모양이다. */
    public LearningTerm(
            TermId termId,
            String label,
            String sentence,
            List<String> aliases,
            String meaning,
            String reading,
            String caveat) {
        this(termId, label, sentence, aliases, meaning, reading, caveat, null);
    }

    /** V1 용어. 다른 이름과 세 문장은 비어 있다. */
    public LearningTerm(TermId termId, String label, String sentence) {
        this(termId, label, sentence, List.of(), "", "", "");
    }

    /** V2 용어. 설명 문장({@code sentence})은 빈 문자열이다. */
    public static LearningTerm v2(
            TermId termId,
            String label,
            List<String> aliases,
            String meaning,
            String reading,
            String caveat) {
        return new LearningTerm(termId, label, "", aliases, meaning, reading, caveat);
    }

    /** V1 초안에 둘 수 있는 모양인가. 다른 이름과 세 문장이 비어 있고 넘겨받지 않는다. */
    public boolean fitsV1() {
        return aliases.isEmpty()
                && meaning.isEmpty()
                && reading.isEmpty()
                && caveat.isEmpty()
                && transferFrom == null;
    }

    /** V2 초안에 둘 수 있는 모양인가. 설명 문장이 비어 있다. */
    public boolean fitsV2() {
        return sentence.isEmpty();
    }

    /** 넘겨받는 중인가. */
    public boolean transferring() {
        return transferFrom != null;
    }

    /** 넘기기를 마친 용어. 이 글이 소유한다. 다른 값은 그대로다. */
    public LearningTerm transferred() {
        return new LearningTerm(termId, label, sentence, aliases, meaning, reading, caveat, null);
    }

    /**
     * 도움말에 보일 문장. 게시 용어 색인의 {@code sentence}로 들어간다(data-model §3-6).
     *
     * @param format 그 판의 형식. V1은 설명 문장, V2는 뜻 문장이다
     */
    public String helpSentence(ArticleFormat format) {
        return format == ArticleFormat.V1 ? sentence : meaning;
    }
}
