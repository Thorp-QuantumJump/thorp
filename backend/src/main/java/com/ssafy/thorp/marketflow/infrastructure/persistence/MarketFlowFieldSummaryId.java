package com.ssafy.thorp.marketflow.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** 분야·등록표 버전·자료 버전을 함께 고정하는 읽기 모델 식별자. */
@Embeddable
public class MarketFlowFieldSummaryId implements Serializable {

    @Column(name = "field_id", length = 64, nullable = false)
    private String fieldId;

    @Column(name = "registry_version", length = 128, nullable = false)
    private String registryVersion;

    @Column(name = "dataset_version", length = 128, nullable = false)
    private String datasetVersion;

    protected MarketFlowFieldSummaryId() {}

    MarketFlowFieldSummaryId(String fieldId, String registryVersion, String datasetVersion) {
        this.fieldId = fieldId;
        this.registryVersion = registryVersion;
        this.datasetVersion = datasetVersion;
    }

    String getFieldId() {
        return fieldId;
    }

    String getRegistryVersion() {
        return registryVersion;
    }

    String getDatasetVersion() {
        return datasetVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MarketFlowFieldSummaryId that)) {
            return false;
        }
        return Objects.equals(fieldId, that.fieldId)
                && Objects.equals(registryVersion, that.registryVersion)
                && Objects.equals(datasetVersion, that.datasetVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldId, registryVersion, datasetVersion);
    }
}
