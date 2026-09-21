package com.ssafy.thorp.myrecord.application.result;

import com.ssafy.thorp.myrecord.application.command.MyRecordListQuery;
import java.util.List;

/**
 * 목록 한 쪽. <b>전체 개수는 없다</b>(research R14) — 세면 매 요청이 원본 표 셋을 훑고, 화면이 그 수를 쓸 자리도 없다.
 *
 * @param nextCursor 다음 쪽이 없으면 null
 */
public record MyRecordPage(List<MyRecordView> items, MyRecordListQuery.Cursor nextCursor) {}
