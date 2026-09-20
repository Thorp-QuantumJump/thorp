package com.ssafy.thorp.macro.presentation.controller;

import com.ssafy.thorp.common.web.response.HttpApiResponse;
import com.ssafy.thorp.macro.application.service.MacroSnapshotQueryService;
import com.ssafy.thorp.macro.presentation.response.MacroSnapshotResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 17개 지표·7개 분야의 현재 공개 스냅샷 (contracts/openapi.yaml).
 *
 * <p><b>인증도 원천 키도 요구하지 않는다</b>(FR-021). 적재된 자료를 읽어 내보낼 뿐이라 사용자에게 공급원을 고르게 하거나 키를 받을 이유가 없다.
 *
 * <p><b>자료가 없어도 200 이다.</b> 완결 자료 버전이 아직 없거나 최근 수집이 실패한 것은 모두 정상적인 조회 상태이고, 그때도 17개 등록 정보와 수집 상태는
 * 내보낸다 — 화면이 "무엇을 보여줄 수 없는지"를 말할 수 있어야 한다(원칙 VI).
 */
@RestController
@RequestMapping("/macro-indicators")
public class MacroSnapshotController {

    private final MacroSnapshotQueryService queryService;

    public MacroSnapshotController(MacroSnapshotQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/snapshot")
    public HttpApiResponse<MacroSnapshotResponse> getSnapshot() {
        return HttpApiResponse.ok(MacroSnapshotResponse.from(queryService.getSnapshot()));
    }
}
