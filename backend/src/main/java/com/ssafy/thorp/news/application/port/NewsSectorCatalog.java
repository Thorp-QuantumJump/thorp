package com.ssafy.thorp.news.application.port;

import java.util.Set;

/**
 * 섹터 식별자 등록표를 읽는 경계(spec FR-005).
 *
 * <p><b>012는 섹터를 만들지 않는다.</b> 섹터 식별자는 spec 001이 소유하고, 뉴스는 그 값을 참조만 한다. 뉴스가 자체 섹터를 선언하면 같은 이름이 두 곳에서
 * 갈라지고 어느 쪽이 맞는지 판단할 근거가 없어진다.
 *
 * <p>이 포트를 두는 이유는 001의 저장소 계약이 바뀌어도 뉴스의 application 계층이 흔들리지 않게 하기 위함이다. 구현은 infrastructure에 둔다.
 *
 * <p>001의 대응표는 아직 발행되지 않았을 수 있다. 그때는 등록된 섹터가 하나도 없는 것이 정상이며, 섹터 지정 조회는 오류가 아니라 빈 화면이다(spec FR-005).
 */
public interface NewsSectorCatalog {

    /**
     * 그 식별자가 001에 등록돼 있는지.
     *
     * <p>대응표가 발행되지 않았으면 어떤 값도 등록돼 있지 않다.
     */
    boolean isRegistered(String sectorId);

    /**
     * 현재 등록된 섹터 식별자 전체.
     *
     * <p>뉴스 연결 규칙이 선언한 섹터 중 001에 실재하는 것만 남길 때 쓴다. 대응표가 없으면 빈 집합이다.
     */
    Set<String> registeredSectorIds();

    /** 001의 대응표가 아직 발행되지 않았는지. 빈 화면과 잘못된 요청을 가르는 기준이다. */
    default boolean isEmpty() {
        return registeredSectorIds().isEmpty();
    }
}
