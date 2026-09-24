package com.ssafy.thorp.news.application.service;

import com.ssafy.thorp.news.application.port.NewsSectorCatalog;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 뉴스를 좁혀 보는 섹터 목록(spec FR-005, Session 2026-09-25).
 *
 * <p>투자 대상 섹터(001)와 뉴스 전용 섹터를 한 목록으로 준다. 화면은 이 목록만 보고 칩을 그리며, 섹터 이름을 따로 들고 있지 않는다.
 */
@Service
public class NewsSectorListService {

    private final NewsSectorCatalog sectors;

    public NewsSectorListService(NewsSectorCatalog sectors) {
        this.sectors = sectors;
    }

    public List<NewsSectorCatalog.SelectableSector> list() {
        return sectors.selectableSectors();
    }
}
