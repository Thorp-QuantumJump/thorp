package com.ssafy.thorp.marketflow.domain.model;

import com.ssafy.thorp.marketflow.domain.vo.FieldId;

/** 008 등록표에서 읽는 표시·출처 메타데이터. */
public record IndicatorMetadata(
        String indicatorId,
        FieldId fieldId,
        String name,
        String unit,
        String transformation,
        String source,
        String agency,
        String publicationPrecision,
        int displayOrder) {}
