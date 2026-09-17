package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import java.time.Instant;

/**
 * 게시 결과(learning-admin-api §6 200).
 *
 * @param checkVersion 통과한 게시 전 검사 규칙 버전
 * @param sectorMapVersion 게시 검사 때 대조한 001 대응표 버전
 */
public record PublishResult(
        String articleId,
        ArticleStatus status,
        int revisionNo,
        String checkVersion,
        String sectorMapVersion,
        Instant publishedAt) {}
