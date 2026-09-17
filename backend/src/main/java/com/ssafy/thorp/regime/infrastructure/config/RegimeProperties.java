package com.ssafy.thorp.regime.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 경제환경 검색 설정. {@code thorp.regime.*}
 *
 * <p><b>코드에 기본값을 박지 않는다.</b> {@code macro.source}가 비면 기동에 실패한다 — 어느 쪽을 쓰는지 배포 환경이 명시해야 한다. 기본값을 두면
 * 설정을 빠뜨린 환경이 조용히 임시 대역으로 돌고, 그때 만들어진 응답은 가짜 자료인 채로 정상으로 보인다.
 *
 * <p>값은 {@code local}이 {@code fixture}, {@code prod}가 {@code macro}다. prod에서 {@code fixture}를 쓰려 하면
 * {@link RegimeFixtureGuard}가 기동을 막는다.
 */
@Validated
@ConfigurationProperties(prefix = "thorp.regime")
public class RegimeProperties {

    /** 008 구현체를 쓴다. */
    public static final String SOURCE_MACRO = "macro";

    /** 008 완성 전까지 쓰는 임시 대역. {@code macro/fixture/} 한 디렉터리에 격리돼 있다(계약 §4.1). */
    public static final String SOURCE_FIXTURE = "fixture";

    @NotNull @Valid private Macro macro;

    public Macro getMacro() {
        return macro;
    }

    public void setMacro(Macro macro) {
        this.macro = macro;
    }

    /** 지표 등록표를 어디서 읽는가. */
    public static class Macro {

        /** {@code macro} 또는 {@code fixture}. 기본값이 없다. */
        @NotNull
        @Pattern(regexp = SOURCE_MACRO + "|" + SOURCE_FIXTURE)
        private String source;

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }
    }
}
