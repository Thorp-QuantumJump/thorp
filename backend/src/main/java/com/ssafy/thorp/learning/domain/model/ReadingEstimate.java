package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.RichTextField;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TableBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 본문 글자 수와 예상 읽기 시간, 최소 머문 시간(research R20). V2 판은 게시할 때 이 값을 판에 넣고, 규칙 파일이 바뀌어도 그 판의 값은 그대로다.
 *
 * <p>식은 규칙 값(분당 글자 수·나눗수·최저 초)을 인자로 받는다. 값은 규칙 파일 v2에 있다.
 *
 * <ul>
 *   <li>보이는 분 = 반올림(글자 수 ÷ 분당 글자 수), 최소 1분. 반올림이 게시 검사의 허용 오차다
 *   <li>최소 머문 시간 = max(최저 초, 올림(반올림 전 예상 읽기 초 ÷ 나눗수))
 * </ul>
 *
 * <p>계산은 정수로 한다. 부동소수 오차로 1,750자 같은 경계가 3분과 4분 사이를 오가지 않게 하려는 것이다.
 *
 * @param bodyChars 본문 글자 수({@link #bodyChars})
 * @param readingMinutes 보이는 분
 * @param minDwellSeconds 로그인 사용자의 완료 판정에 쓰는 최소 머문 시간
 */
public record ReadingEstimate(int bodyChars, int readingMinutes, int minDwellSeconds) {

    public ReadingEstimate {
        if (bodyChars < 0 || readingMinutes < 1 || minDwellSeconds < 0) {
            throw new IllegalArgumentException(
                    "invalid reading estimate: "
                            + bodyChars
                            + "/"
                            + readingMinutes
                            + "/"
                            + minDwellSeconds);
        }
    }

    public static ReadingEstimate of(
            int bodyChars, int charsPerMinute, int minDwellDivisor, int minDwellFloorSeconds) {
        if (charsPerMinute < 1 || minDwellDivisor < 1 || minDwellFloorSeconds < 0) {
            throw new IllegalArgumentException("invalid reading rules");
        }
        long dwell = ceilDiv(bodyChars * 60L, (long) charsPerMinute * minDwellDivisor);
        return new ReadingEstimate(
                bodyChars,
                minutes(bodyChars, charsPerMinute),
                (int) Math.max(minDwellFloorSeconds, dwell));
    }

    /** 보이는 분. 반올림하고 최소 1분이다. LEGACY 판은 조회할 때 이 값만 계산한다. */
    public static int minutes(int bodyChars, int charsPerMinute) {
        long rounded = (2L * bodyChars + charsPerMinute) / (2L * charsPerMinute);
        return (int) Math.max(1, rounded);
    }

    /**
     * 독자에게 보이는 본문 글자 가운데 공백을 뺀 글자 수(R20).
     *
     * <p>세는 것: 핵심 요약, 소제목, 문단, 용어 블록의 이름과 문장(V1은 설명 문장, V2는 뜻·읽는 법·한계), 가상의 계산 예, 오해, 목록, 표의 머리 칸과
     * 칸, 도식 설명, 제도 안내. 세지 않는 것: 제목·질문·확인 문제·예시·출처 표시, 표 제목, 도식의 제목·단위·계열 이름·축 이름.
     *
     * @param terms 그 글의 용어. 용어 블록의 문장을 여기서 찾는다
     */
    public static int bodyChars(List<ArticleBlock> blocks, List<LearningTerm> terms) {
        Map<TermId, LearningTerm> byId = new HashMap<>();
        terms.forEach(term -> byId.put(term.termId(), term));
        int count = 0;
        for (ArticleBlock block : blocks) {
            switch (block) {
                case HeadingBlock heading -> count += visible(heading.text());
                case TermBlock termBlock -> {
                    LearningTerm term = byId.get(termBlock.termId());
                    if (term != null) {
                        count +=
                                visible(term.label())
                                        + visible(term.sentence())
                                        + visible(term.meaning())
                                        + visible(term.reading())
                                        + visible(term.caveat());
                    }
                }
                case TableBlock table -> {
                    for (String column : table.columns()) {
                        count += visible(column);
                    }
                    count += richTextChars(table);
                }
                case DiagramBlock diagram -> count += visible(diagram.description());
                default -> count += richTextChars(block);
            }
        }
        return count;
    }

    private static int richTextChars(ArticleBlock block) {
        int count = 0;
        for (RichTextField field : block.richTextFields()) {
            count += visible(field.text().plainText());
        }
        return count;
    }

    /** 공백 문자를 뺀 글자(코드 포인트) 수. */
    static int visible(String text) {
        return (int)
                text.codePoints()
                        .filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
                        .count();
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
