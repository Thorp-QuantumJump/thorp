package com.ssafy.thorp.marketflow.application.repository;

import com.ssafy.thorp.marketflow.domain.model.MarketFlowField;
import java.time.Instant;
import java.util.List;

/** 버전별로 미리 만든 분야 요약 읽기 모델의 저장 경계. 같은 버전의 행은 덮어쓰지 않는다. */
public interface MarketFlowFieldSummaryRepository {

    void saveIfAbsent(
            String registryVersion,
            String datasetVersion,
            List<MarketFlowField> fields,
            Instant builtAt);

    List<MarketFlowField> findAll(String registryVersion, String datasetVersion);
}
