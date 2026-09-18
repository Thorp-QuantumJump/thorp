package com.ssafy.thorp.learning.infrastructure.admin;

import com.ssafy.thorp.learning.application.port.LearningAdminPolicy;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 설정 허용 목록 {@code thorp.learning.admin-user-ids}(쉼표로 구분한 사용자 ID)로 관리 계정을 판정한다(research R5).
 *
 * <p><b>비어 있으면 아무도 관리자가 아니다.</b> 014에 역할이 없어 둔 임시 구현이며, 014가 역할을 주면 이 클래스만 바꾼다.
 *
 * <p>숫자가 아닌 항목이 있으면 기동에 실패한다. 오타 난 목록을 건너뛰고 뜨면 운영자가 왜 게시할 수 없는지 알 방법이 없다(원칙 VI).
 */
@Component
public class ConfiguredLearningAdminPolicy implements LearningAdminPolicy {

    private final Set<Long> adminUserIds;

    public ConfiguredLearningAdminPolicy(
            @Value("${thorp.learning.admin-user-ids}") String adminUserIds) {
        this.adminUserIds = parse(adminUserIds);
    }

    @Override
    public boolean isAdmin(long userId) {
        return adminUserIds.contains(userId);
    }

    private static Set<Long> parse(String raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(trimmed));
            } catch (NumberFormatException invalid) {
                throw new IllegalStateException(
                        "thorp.learning.admin-user-ids must be comma-separated user ids: "
                                + trimmed,
                        invalid);
            }
        }
        return Set.copyOf(ids);
    }
}
