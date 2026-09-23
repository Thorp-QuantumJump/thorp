package com.ssafy.thorp.learning.application.command;

/**
 * 게시 취소(learning-admin-api §7).
 *
 * @param actorUserId 게시 취소하는 관리 계정. 게시 기록에 남는다(FR-018)
 */
public record UnpublishCommand(long actorUserId) {}
