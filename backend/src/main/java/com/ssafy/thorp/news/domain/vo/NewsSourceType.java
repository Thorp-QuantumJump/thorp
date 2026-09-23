package com.ssafy.thorp.news.domain.vo;

/**
 * 기사를 발견한 뉴스원 종류. RSS가 주 뉴스원이고 GDELT는 국제 사건·해외 보도의 보완원이다(spec FR-009).
 *
 * <p>{@code GDELT_FILE}은 배포 파일에서 온 레코드다. 배포 레코드만으로 검증된 발행 시각과 제목을 확보하지 못하면 표시 후보로 만들지 않는다.
 */
public enum NewsSourceType {
    RSS,
    GDELT_DOC,
    GDELT_FILE;

    /** GDELT 계열인지 여부. 기록 시각을 발행 시각으로 쓰지 않는 판단에 쓴다(spec FR-012). */
    public boolean isGdelt() {
        return this != RSS;
    }
}
