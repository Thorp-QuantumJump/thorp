package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.model.ReleaseLogEntry;
import java.util.List;

/**
 * 변경 기록({@code learning_release_log}, data-model §3-7).
 *
 * <p><b>추가와 조회만 있다.</b> 기록을 고치거나 지우는 메서드를 두지 않는다. 변경 기록은 그때의 사실이다(research R27).
 */
public interface ReleaseLogRepository {

    /**
     * 한 줄을 더한다. 같은 {@code entryKey}가 이미 있으면 제약({@code uq_learning_release_log_entry_key})에 걸린다. 한
     * 단계는 한 번만 공개되고 글의 첫 게시는 한 번뿐이므로 정상 흐름에서는 일어나지 않는다.
     */
    void append(ReleaseLogEntry entry);

    /** 모든 줄. 최신 순서(시각이 같으면 나중에 더한 줄이 먼저)다. */
    List<ReleaseLogEntry> findAllLatestFirst();
}
