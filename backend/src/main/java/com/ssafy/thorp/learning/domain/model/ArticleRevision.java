package com.ssafy.thorp.learning.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 불변 게시 판(data-model §3-4). 만든 뒤 고치거나 지우지 않는다. setter도, 바꾼 사본을 만드는 메서드도 두지 않는다(원칙 VIII).
 *
 * <p><b>불변식</b>: V1 판은 {@code LEGACY}, V2 판은 {@code CURRENT}다(research R16).
 *
 * @param id 저장 전이면 null
 * @param revisionNo 글마다 1부터 오른다. 게시 취소 뒤 다시 게시해도 이어서 오른다
 * @param edition 판의 종류
 * @param checkVersion 통과한 게시 전 검사 규칙 파일 버전
 * @param sectorMapVersion 게시 검사 때 대조한 001 대응표 버전. 예시가 없는 V2 판은 대응표를 읽지 않으므로 {@link #NOT_CHECKED}다
 * @param publishedByUserId 게시한 관리 계정의 사용자 식별자. 이메일·닉네임은 담지 않는다
 */
public record ArticleRevision(
        Long id,
        int revisionNo,
        Edition edition,
        ArticleSnapshot snapshot,
        String checkVersion,
        String sectorMapVersion,
        long publishedByUserId,
        Instant publishedAt) {

    /** 대응표를 대조하지 않은 판의 {@code sectorMapVersion}. 판정하지 않은 것을 판정한 것처럼 적지 않는다(원칙 VI). */
    public static final String NOT_CHECKED = "NOT_CHECKED";

    public ArticleRevision {
        if (revisionNo < 1) {
            throw new IllegalArgumentException("revisionNo must be >= 1: " + revisionNo);
        }
        Objects.requireNonNull(edition, "edition must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(checkVersion, "checkVersion must not be null");
        Objects.requireNonNull(sectorMapVersion, "sectorMapVersion must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        Edition expected = snapshot.format() == ArticleFormat.V1 ? Edition.LEGACY : Edition.CURRENT;
        if (edition != expected) {
            throw new IllegalArgumentException(
                    "a " + snapshot.format() + " snapshot belongs to a " + expected + " revision");
        }
    }
}
