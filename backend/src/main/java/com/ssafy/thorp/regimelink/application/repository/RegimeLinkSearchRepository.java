package com.ssafy.thorp.regimelink.application.repository;

import com.ssafy.thorp.regimelink.domain.model.RegimeLinkSearch;
import com.ssafy.thorp.regimelink.domain.vo.LinkSearchId;
import java.util.Optional;

/**
 * 검색 경로별 연결 기록 저장소 (data-model §3).
 *
 * <p>{@code UNIQUE (owner_user_id, client_request_id)}가 검색 생성의 멱등을 강제한다 — 같은 요청자가 같은 키로 다시 보내면 기존
 * 기록을 돌려주고 새 행을 만들지 않는다(REST §1b).
 */
public interface RegimeLinkSearchRepository {

    Optional<RegimeLinkSearch> findBySearchId(LinkSearchId searchId);

    Optional<RegimeLinkSearch> findByClientRequest(Long ownerUserId, String clientRequestId);

    RegimeLinkSearch save(RegimeLinkSearch search);
}
