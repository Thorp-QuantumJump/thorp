package com.ssafy.thorp.learning.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 불변 게시 판(data-model §3-3). 만든 뒤 고치거나 지우지 않는다. setter도, 바꾼 사본을 만드는 메서드도 두지 않는다(원칙 VIII).
 *
 * @param id 저장 전이면 null
 * @param revisionNo 글마다 1부터 오른다. 게시 취소 뒤 다시 게시해도 이어서 오른다
 * @param checkVersion 통과한 게시 전 검사 규칙 파일 버전
 * @param sectorMapVersion 게시 검사 때 대조한 001 대응표 버전
 * @param publishedByUserId 게시한 관리 계정의 사용자 식별자. 이메일·닉네임은 담지 않는다
 */
public record ArticleRevision(
        Long id,
        int revisionNo,
        ArticleSnapshot snapshot,
        String checkVersion,
        String sectorMapVersion,
        long publishedByUserId,
        Instant publishedAt) {

    public ArticleRevision {
        if (revisionNo < 1) {
            throw new IllegalArgumentException("revisionNo must be >= 1: " + revisionNo);
        }
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(checkVersion, "checkVersion must not be null");
        Objects.requireNonNull(sectorMapVersion, "sectorMapVersion must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }
}
