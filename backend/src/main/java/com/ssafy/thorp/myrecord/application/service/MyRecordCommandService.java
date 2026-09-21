package com.ssafy.thorp.myrecord.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.domain.model.MyRecord;
import com.ssafy.thorp.myrecord.domain.vo.RecordName;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 기록 정리(spec 006 US5, FR-017).
 *
 * <p><b>삭제는 {@code my_record} 행만 건드린다.</b> 다른 표에 DELETE를 보내지 않는다 — 기록은 공용 계산 결과를 <b>가리킬 뿐</b>이고,
 * 지우는 것은 그 사용자의 소유 기록이다(research R6). 같은 결과를 가리키는 다른 사용자의 항목은 그대로 열리고 그 공개 토큰도 살아 있다(SC-007).
 *
 * <p><b>되돌리는 경로를 만들지 않는다.</b> 복구 기능은 만들지 않기로 했고(data-model §5), 그래서 지운 뒤 상태는 하나뿐이다.
 *
 * <p>남의 것·없는 것·이미 지운 것을 <b>한 가지 404</b>로 답한다. 403은 그 식별자가 존재한다는 사실을 알려 준다 — 읽기 경로({@code
 * MyRecordQueryService})와 같은 규칙이다.
 */
@Service
public class MyRecordCommandService {

    private final MyRecordRepository records;
    private final Clock clock;

    public MyRecordCommandService(MyRecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    /**
     * 이름을 바꾼다. <b>이름만</b> 바뀌고 저장된 결과·참조 버전은 그대로다(FR-017, SC-001).
     *
     * <p>같은 이름이 여럿 있어도 막지 않는다(research R12) — 이름은 찾기 위한 표시이고 식별은 {@code recordId}가 한다.
     *
     * @throws IllegalArgumentException 앞뒤 공백을 지운 뒤 비었거나 120자를 넘으면. 전역 처리기가 400으로 옮긴다. 자동 이름으로 되돌리지
     *     않는다 — 사용자가 지운 이름을 서버가 되살리면 그 값은 사용자의 뜻이 아니다
     */
    @Transactional
    public void rename(long ownerId, String recordId, String name) {
        MyRecord record = require(ownerId, recordId);
        records.renameTo(record.id(), record.rename(RecordName.of(name)).name().value());
    }

    /**
     * 지운다. {@code deleted_at}을 채우는 것이 전부다.
     *
     * @return 지운 시각. 화면이 그대로 보인다
     */
    @Transactional
    public Instant delete(long ownerId, String recordId) {
        MyRecord record = require(ownerId, recordId);
        Instant now = clock.instant();
        // 도메인이 이미 지운 항목을 거절한다. 여기 오기 전에 require 가 걸러 내므로 정상 경로에서는
        // 던지지 않고, 동시에 두 번 눌렀을 때의 안전망이다.
        record.delete(now);
        records.softDelete(record.id(), now);
        return now;
    }

    /** 없는 것·남의 것·이미 지운 것이 모두 여기서 같은 404가 된다. */
    private MyRecord require(long ownerId, String recordId) {
        return records.findByRecordId(recordId)
                .filter(record -> record.ownedBy(ownerId))
                .filter(record -> !record.isDeleted())
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND,
                                        "기록을 찾을 수 없습니다: " + recordId));
    }
}
