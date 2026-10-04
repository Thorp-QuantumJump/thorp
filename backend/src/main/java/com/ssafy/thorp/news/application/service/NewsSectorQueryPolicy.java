package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.exception.NewsQueryException;
import com.ssafy.thorp.news.application.port.NewsSectorCatalog;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 요청이 지정한 섹터 식별자를 spec 001의 등록표와 대조한다(spec FR-005).
 *
 * <p><b>012는 섹터를 만들지 않는다.</b> 식별자의 형식도 001이 정한다. 그래서 여기서 정규식으로 모양을 따지지 않고 등록표에 있는지만 본다. 형식을 따로 적어
 * 두면 001이 규칙을 바꾼 날 001은 발급할 수 있는데 뉴스는 거부하는 값이 생긴다.
 *
 * <p>두 가지 '없음'을 가른다.
 *
 * <ul>
 *   <li><b>대응표가 아직 발행되지 않음</b> — 오류가 아니다. 사용자가 고칠 수 있는 것이 없고 파이프라인이 대응표를 발행하면 달라진다. 빈 화면으로 답한다
 *   <li><b>대응표는 있는데 그 값이 없음</b> — 400이다. 사용자가 다른 섹터를 고르면 달라진다
 * </ul>
 *
 * <p>둘을 뒤집으면 001 적재 전에 모든 섹터 조회가 오류가 되어, 뉴스와 무관한 파이프라인 일정이 뉴스 화면을 막는다.
 */
@Component
public class NewsSectorQueryPolicy {

    private final NewsSectorCatalog sectors;

    public NewsSectorQueryPolicy(NewsSectorCatalog sectors) {
        this.sectors = sectors;
    }

    /**
     * 그 섹터로 조회를 진행해도 되는지.
     *
     * @return 대응표가 아직 없어 조회할 자료가 없으면 거짓. 이때는 빈 화면으로 답한다.
     * @throws NewsQueryException 대응표는 있는데 등록되지 않은 식별자일 때.
     */
    public boolean canQuery(String sectorId) {
        if (sectorId == null || sectorId.isBlank()) {
            return true;
        }
        if (sectors.isRegistered(sectorId)) {
            // 뉴스 전용 섹터는 001 대응표와 무관하게 늘 여기서 통과한다(Session 2026-09-25).
            return true;
        }
        if (sectors.isEmpty()) {
            // 001의 대응표가 아직 발행되지 않았다. 어떤 값도 확인할 수 없으므로 거부하지 않고 빈 화면으로 답한다.
            return false;
        }
        // 값을 메시지에 싣지 않는다. 요청 값을 그대로 되돌려 주면 가림 규칙이 응답에서 무너진다(spec FR-021).
        throw new NewsQueryException("등록되지 않은 섹터입니다.");
    }

    /**
     * 여러 섹터를 한 번에 대조하고 실제로 물을 섹터만 돌려준다(2026-10-02).
     *
     * <p>등록표를 한 번만 읽는다. 섹터마다 {@link #canQuery}를 부르면 분류 하나(섹터 여섯)를 묻는 요청 하나가 001 대응표를 여섯 번 읽는다.
     *
     * <p>판정은 {@link #canQuery}와 같다. 대응표는 있는데 모르는 값이 하나라도 섞이면 400이다. 대응표가 아직 없으면 투자 대상 섹터는 확인할 수
     * 없으므로 거부하지 않고 빼고, 012가 소유한 뉴스 전용 섹터만 남긴다. 여럿을 묻는 뜻이 '그중 하나 이상과 연결된 묶음'이라, 확인할 수 없는 섹터 때문에 확인할
     * 수 있는 섹터의 뉴스까지 비우지 않는다.
     *
     * @param sectorIds 요청이 지정한 섹터. 비어 있지 않다.
     * @return 물을 섹터. 비었으면 빈 화면으로 답한다.
     * @throws NewsQueryException 대응표는 있는데 등록되지 않은 식별자가 섞였을 때.
     */
    public List<String> queryableOf(List<String> sectorIds) {
        Set<String> registered = sectors.registeredSectorIds();
        if (registered.containsAll(sectorIds)) {
            return sectorIds;
        }
        if (sectors.isEmpty()) {
            return sectorIds.stream().filter(registered::contains).toList();
        }
        throw new NewsQueryException("등록되지 않은 섹터입니다.");
    }
}
