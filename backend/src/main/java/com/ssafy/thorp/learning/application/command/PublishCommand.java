package com.ssafy.thorp.learning.application.command;

/**
 * 게시(learning-admin-api §6).
 *
 * @param expectedDraftVersion 운영자가 보고 게시하는 초안 판 번호. 다르면 게시하지 않는다
 * @param actorUserId 게시하는 관리 계정. 판과 게시 기록에 남는다(FR-018)
 */
public record PublishCommand(int expectedDraftVersion, long actorUserId) {}
