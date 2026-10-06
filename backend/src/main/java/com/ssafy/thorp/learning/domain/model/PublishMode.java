package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.exception.FormatOutdatedException;
import com.ssafy.thorp.learning.domain.exception.StageNotReleasedException;

/**
 * 글마다 게시의 방식(data-model §10-1, research R16). 서버가 초안 형식과 초안 단계의 공개 여부로 정한다. 운영자가 고르지 않는다.
 *
 * <table>
 *   <caption>게시 방식</caption>
 *   <tr><th>초안 형식</th><th>초안 단계</th><th>결과</th></tr>
 *   <tr><td>V2</td><td>공개됨</td><td>v2 검사 → {@code CURRENT} 판</td></tr>
 *   <tr><td>V2</td><td>공개 전</td><td>409 {@code LEARNING_STAGE_NOT_RELEASED}</td></tr>
 *   <tr><td>V1</td><td>공개 전</td><td>v1 검사 → {@code LEGACY} 판(이전 판 고치기)</td></tr>
 *   <tr><td>V1</td><td>공개됨</td><td>409 {@code LEARNING_FORMAT_OUTDATED}</td></tr>
 * </table>
 */
public enum PublishMode {

    /** 이전 판 고치기. 최소 구성·확인 문제는 묻지 않는다. */
    LEGACY_FIX(Edition.LEGACY),

    /** 새 기준 글의 게시. */
    CURRENT(Edition.CURRENT);

    private final Edition edition;

    PublishMode(Edition edition) {
        this.edition = edition;
    }

    /** 이 방식이 만드는 판의 종류. */
    public Edition edition() {
        return edition;
    }

    /**
     * @param stageReleased 초안 배치의 단계가 공개됐는가
     * @throws StageNotReleasedException V2 초안인데 단계가 공개 전이다
     * @throws FormatOutdatedException V1 초안인데 단계가 공개됐다
     */
    public static PublishMode decide(ArticleFormat format, boolean stageReleased) {
        return switch (format) {
            case V2 -> {
                if (!stageReleased) {
                    throw new StageNotReleasedException();
                }
                yield CURRENT;
            }
            case V1 -> {
                if (stageReleased) {
                    throw new FormatOutdatedException();
                }
                yield LEGACY_FIX;
            }
        };
    }
}
