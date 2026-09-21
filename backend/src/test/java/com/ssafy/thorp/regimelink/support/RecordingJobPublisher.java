package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.port.RegimeLinkJobPublisher;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;
import java.util.ArrayList;
import java.util.List;

/** 발행된 작업을 모아 두는 시험용 발행기. <b>몇 건 나갔는가</b>가 대부분의 단언이라 개수를 세기 쉽게 둔다. */
public class RecordingJobPublisher implements RegimeLinkJobPublisher {

    private final List<RegimeLinkJob> published = new ArrayList<>();

    @Override
    public void publish(RegimeLinkJob job) {
        published.add(job);
    }

    public List<RegimeLinkJob> published() {
        return List.copyOf(published);
    }

    public RegimeLinkJob only() {
        if (published.size() != 1) {
            throw new IllegalStateException("발행된 작업이 하나가 아닙니다: " + published.size());
        }
        return published.get(0);
    }
}
