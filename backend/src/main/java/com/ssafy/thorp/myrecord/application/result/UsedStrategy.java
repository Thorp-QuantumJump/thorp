package com.ssafy.thorp.myrecord.application.result;

import java.util.Map;

/**
 * 사용자가 단일 분석에서 써 본 전략·설정값 조합 하나({@code GET /my-records/used-strategies}, spec 005 비교 후보).
 *
 * <p>결과가 아니라 <b>조합</b>이다. 005 비교는 조건(섹터·기간·비용)을 기준 결과에서 가져오고 이 조합만 상대편에 붙여 004 에 새로 요청한다. 그래서 어느
 * 실행에서 나왔는지, 그 실행이 끝났는지는 싣지 않는다 — 같은 조건의 완료 실행이 있으면 004 의 동일 버전 재사용이 알아서 돌려준다.
 *
 * @param recordName 그 조합을 가리키는 살아 있는 내 기록 항목의 이름. 사용자가 붙인 이름이라 화면이 후보의 이름표로 쓴다. 항목을 지웠거나 없으면 {@code
 *     null}이고 화면은 전략 이름으로 대신한다(006 FR-017의 삭제 표시를 따른다)
 * @param rulesVersion 그 실행이 쓴 규칙 버전. 참고 표시용이다 — 새 요청은 현재 등록표의 버전을 싣는다
 * @param parameters 003 등록표 축 이름 기준의 설정값. 접수가 저장한 값 그대로다
 */
public record UsedStrategy(
        String strategyId,
        String recordName,
        String rulesVersion,
        Map<String, Object> parameters) {}
