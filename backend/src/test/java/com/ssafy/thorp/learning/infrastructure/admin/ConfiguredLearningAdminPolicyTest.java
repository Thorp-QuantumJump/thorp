package com.ssafy.thorp.learning.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfiguredLearningAdminPolicyTest {

    @Test
    @DisplayName("빈 값이면 아무도 관리자가 아니다")
    void emptyMeansNoAdmin() {
        ConfiguredLearningAdminPolicy policy = new ConfiguredLearningAdminPolicy("");

        assertThat(policy.isAdmin(1)).isFalse();
        assertThat(policy.isAdmin(0)).isFalse();
    }

    @Test
    @DisplayName("공백이 섞인 쉼표 목록도 읽는다")
    void parsesListWithSpaces() {
        ConfiguredLearningAdminPolicy policy = new ConfiguredLearningAdminPolicy("1, 2 ,");

        assertThat(policy.isAdmin(1)).isTrue();
        assertThat(policy.isAdmin(2)).isTrue();
        assertThat(policy.isAdmin(3)).isFalse();
    }

    @Test
    @DisplayName("숫자가 아닌 항목이 있으면 기동에 실패한다")
    void nonNumericFails() {
        assertThatThrownBy(() -> new ConfiguredLearningAdminPolicy("1, admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin");
    }
}
