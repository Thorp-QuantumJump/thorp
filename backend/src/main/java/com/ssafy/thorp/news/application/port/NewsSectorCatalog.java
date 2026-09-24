package com.ssafy.thorp.news.application.port;

import java.util.Set;

/**
 * 섹터 식별자 등록표를 읽는 경계(spec FR-005).
 *
 * <p><b>투자 대상 섹터는 001이 소유한다.</b> 뉴스는 그 식별자를 참조만 하고 새로 만들지 않는다. 다만 투자 대상과 대응하지 않는 <b>뉴스 전용 섹터</b>는
 * 012가 소유한다(spec FR-005, Session 2026-09-25). 수집한 기사 대부분이 투자 대상 밖이라 좁혀 볼 길이 없었기 때문이다. 둘이 섞이지 않게 뉴스
 * 전용 섹터는 {@code NEWS_}로 시작한다.
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

    /**
     * 뉴스를 좁혀 보는 칩 하나.
     *
     * @param sectorId 섹터 식별자.
     * @param name 화면 이름. 001 섹터는 001의 이름이다.
     * @param investable 001 대응표의 투자 대상 섹터인지. 거짓이면 뉴스 전용 섹터다.
     */
    record SelectableSector(String sectorId, String name, boolean investable) {}

    /**
     * 지금 고를 수 있는 뉴스 섹터를 화면 순서로 돌려준다(spec FR-005, Session 2026-09-25).
     *
     * <p>001 섹터는 001에 열려 있을 때만 들어가고, 뉴스 전용 섹터는 늘 들어간다.
     */
    default java.util.List<SelectableSector> selectableSectors() {
        return java.util.List.of();
    }
}
