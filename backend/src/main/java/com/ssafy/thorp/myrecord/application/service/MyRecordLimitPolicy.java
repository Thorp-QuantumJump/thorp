package com.ssafy.thorp.myrecord.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.myrecord.application.port.MyRecordLimits;
import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
import com.ssafy.thorp.myrecord.domain.model.RecordKind;
import com.ssafy.thorp.paramsearch.application.port.SaveSlotGuard;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 탐색 묶음 저장 상한(spec 006 FR-018, research R11).
 *
 * <p><b>값이 아니라 장치가 산출물이다.</b> 상한값은 {@link MyRecordLimits}가 설정에서 실어 오고 PD-13이 실측 뒤 정한다. 여기서는 넘었는지만
 * 본다 — 004의 {@code ExecutionLimitPolicy}와 같은 모양이다.
 *
 * <p><b>판정 자리는 접수 직전이다.</b> 계산이 끝난 뒤 저장 단계에서 막으면 이미 쓴 계산 자원이 버려진다 — Edge Cases가 "새 탐색을 시작하기 전에
 * 알린다"로 정했다. 007 접수({@code ParamSearchService.create})가 어떤 행을 쓰기도 전에 {@link SaveSlotGuard}로 부른다 —
 * 007은 무엇을 세는지 알지 못하고, 그래야 화살표가 {@code myrecord → paramsearch} 하나로 남는다.
 *
 * <p><b>거절은 429가 아니라 409다.</b> 429는 실행 자원(큐에 쌓이는 양) 이야기이고 이것은 저장 자리 이야기라 코드를 나눈다(spec FR-018). 기존
 * 항목을 지우지 않는다 — 무엇을 지울지는 사용자가 정한다.
 *
 * <p><b>세는 구간을 잠그지 않는다.</b> 동시에 들어온 두 접수가 같은 수를 읽으면 상한을 한둘 넘겨 저장될 수 있다. 큐 한도와 달리 넘겨도 자원을 더 쓰지 않고 다음
 * 접수가 다시 걸리므로, 자문 잠금을 두 도메인에 걸치게 만드는 값이 없다고 봤다.
 */
// ponytail: 세는 구간에 잠금이 없다. 동시 접수가 상한을 살짝 넘길 수 있고, 정확한 개수가 필요해지면
// ExecutionLimitPolicy처럼 사용자 키 자문 잠금을 잡는다.
@Component
public class MyRecordLimitPolicy implements SaveSlotGuard {

    private final MyRecordRepository records;
    private final MyRecordLimits limits;

    public MyRecordLimitPolicy(MyRecordRepository records, MyRecordLimits limits) {
        this.records = records;
        this.limits = limits;
    }

    /**
     * 이 사용자가 탐색 묶음을 하나 더 저장해도 되는지 본다. 넘었으면 {@code 409 RECORD_LIMIT_REACHED}를 던진다.
     *
     * <p>세는 것은 <b>살아 있는</b> 묶음 항목뿐이다 — 지운 항목이 자리를 계속 차지하면 "지운 뒤 다시 시작하라"는 안내가 거짓이 된다. <b>사본({@code
     * is_copy})도 센다</b> — 같은 원본을 가리켜도 목록에서 한 줄을 차지하므로 저장 자리를 먹는다. 재사용 판정({@code
     * findActiveByOwnerAnd*})이 사본을 빼는 것과 조건이 다른 것은 그래서다. 사본을 만드는 경로가 생기면 그쪽도 이 판정을 지나야 한다.
     *
     * <p>본문에 현재 개수와 상한을 실어 보낸다. 화면이 "N/M"을 보이려면 숫자가 필요하고, 문장에 섞어 두면 문구를 다듬다가 조용히 깨진다.
     */
    @Override
    public void requireSaveSlot(long ownerId) {
        int limit = limits.searchBundle();
        long current = records.countActiveByOwnerAndKind(ownerId, RecordKind.SEARCH_BUNDLE);
        if (current < limit) {
            return;
        }
        throw new BusinessException(
                ErrorCode.RECORD_LIMIT_REACHED,
                ErrorCode.RECORD_LIMIT_REACHED.getMessage(),
                Map.of("current", current, "limit", limit));
    }
}
