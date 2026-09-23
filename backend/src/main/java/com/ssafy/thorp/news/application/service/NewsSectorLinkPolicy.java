package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.port.NewsSectorCatalog;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 기사에 붙일 섹터 식별자를 spec 001에 실재하는 값으로 좁힌다(spec FR-005·FR-010).
 *
 * <p>뉴스의 주제 등록표(`news-link-v1`)는 주제 키마다 연결할 섹터 식별자를 적어 둔다. 그 값은 012가 정한 것이 아니라 <b>001의 식별자를 적어 놓은
 * 것</b>이다. 적어 놓은 시점과 지금이 다를 수 있다 — 001이 대응표 버전을 새로 열면서 어떤 섹터가 빠질 수 있고, 등록표에 오타가 있을 수도 있다.
 *
 * <p>그래서 연결 직전에 한 번 더 대조한다. 대조 없이 저장하면 001에 없는 섹터로 연결된 기사가 생기고, 그 기사는 어떤 섹터 조회에도 걸리지 않으면서 저장소에는 연결이
 * 남는다. 나중에 왜 안 나오는지 찾기 어려운 종류의 결함이다.
 *
 * <p>001의 대응표가 아직 발행되지 않았으면 남는 섹터가 없다. 이것은 정상이다 — 기사는 주제로만 연결되고, 섹터 지정 조회는 빈 화면이 된다. 대응표가 발행된 뒤 다시
 * 수집하면 연결이 생긴다.
 *
 * <p>연결 규칙 버전과 근거 키는 {@link NewsRelevanceClassifier}가 만든 값을 그대로 쓴다. 여기서 새 근거를 만들지 않는다(원칙 VIII).
 */
@Component
public class NewsSectorLinkPolicy {

    private final NewsSectorCatalog sectors;

    public NewsSectorLinkPolicy(NewsSectorCatalog sectors) {
        this.sectors = sectors;
    }

    /**
     * 등록표가 제안한 섹터 중 001에 실재하는 것만 남긴다.
     *
     * <p>입력 순서를 보존한다. 저장되는 연결의 순서가 조회마다 달라지면 같은 입력에 다른 결과가 나온 것처럼 보인다.
     */
    public Set<String> resolve(Set<String> proposedSectorIds) {
        if (proposedSectorIds == null || proposedSectorIds.isEmpty()) {
            return Set.of();
        }
        Set<String> registered = sectors.registeredSectorIds();
        if (registered.isEmpty()) {
            return Set.of();
        }
        Set<String> resolved = new LinkedHashSet<>();
        for (String sectorId : proposedSectorIds) {
            if (registered.contains(sectorId)) {
                resolved.add(sectorId);
            }
        }
        return resolved;
    }
}
