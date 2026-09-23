package com.ssafy.thorp.paramsearch.infrastructure.config;

import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 자동 탐색 모듈 설정. {@link ParamSearchProperties} 등록과 진행 전진용 실행기를 둔다.
 *
 * <p>애플리케이션 전역에 {@code @ConfigurationPropertiesScan}을 걸지 않고 모듈 안에서 등록한다. 전역 스캔은 다른 파트가 만든 설정 클래스까지
 * 함께 켜서 이 모듈의 변경이 바깥에 영향을 준다.
 *
 * <p><b>{@code thorp.param-search.enabled=true}일 때만 로드된다.</b> {@link ParamSearchProperties}의 네 한도가
 * {@code @NotNull}인데 그 값들이 PD-05·PD-06 확정 대기라 공통 설정에서 비어 있다. 이 설정이 무조건 로드되면 값을 채우지 않은 환경에서 <b>자동
 * 탐색만 죽는 것이 아니라 애플리케이션 전체가 기동에 실패</b>한다 — 007이 아직 아무 엔드포인트도 노출하지 않는 단계에서 기존 기능까지 함께 내려가는 것은 받아들일 수
 * 없다.
 *
 * <p>키가 없으면 꺼진 것으로 본다({@code matchIfMissing} 기본값). 그래서 값을 모르는 환경은 그냥 부팅되고, 켜는 순간 네 값이 필수가 된다. 값을
 * 추측해 기본값으로 박는 것(constitution "AI 작업 방식" 위반)과 배포를 깨는 것 사이의 세 번째 선택이다.
 *
 * <p><b>다음 묶음에게</b>: {@link ParamSearchProperties}를 주입하는 빈은 이 설정과 같은 조건을 가져야 한다. 같은
 * {@code @ConditionalOnProperty}를 붙이거나 이 클래스의 {@code @Bean}으로 선언한다. 조건 없이 {@code @Service}로 두면 자동
 * 탐색이 꺼진 환경에서 그 빈이 프로퍼티를 찾지 못해 기동이 실패하고, 이 플래그를 둔 이유가 없어진다.
 */
@Configuration
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ParamSearchProperties.class)
public class ParamSearchConfig {

    /**
     * 설정값을 application 계층이 쓰는 모양으로 옮긴다.
     *
     * <p>유스케이스가 {@link ParamSearchProperties}를 직접 주입받으면 application → infrastructure 참조가 되어 계층 규칙을
     * 어긴다({@code ArchitectureTest}). 변환을 여기서 한 번 하면 설정 형식이 바뀌어도 유스케이스가 흔들리지 않는다.
     */
    @Bean
    SearchLimits searchLimits(ParamSearchProperties properties) {
        return new SearchLimits(
                properties.getMaxCandidates(),
                properties.getDispatchWindow(),
                properties.getTimeLimitSeconds(),
                properties.getConsecutiveFailureLimit(),
                properties.provisionalNames());
    }

    /**
     * 후보 결과를 묶음에 반영하는 일을 <b>사용자가 기다리는 스레드 밖에서</b> 하기 위한 실행기.
     *
     * <p>필요한 이유: 접수가 후보를 발행할 때 그 조합이 이미 계산돼 있으면 004가 그 자리에서 완료를 알린다(재사용, spec 004 R-02). 그 완료를 접수
     * 스레드에서 처리하면 다음 후보가 발행되고 그것도 즉시 끝나 <b>묶음 전체가 POST 응답 안에서 돌아간다</b>. 계약은 접수가 202로 바로 끝나고 진행은 폴링으로
     * 보이는 것이다(FR-008) — 화면은 3초마다 조회하며 완료된 후보를 받아 가야 한다.
     *
     * <p><b>스레드가 하나다.</b> 한 묶음의 후보는 어차피 묶음 행을 잠그고 차례로 처리되므로 늘려도 빨라지지 않고, 늘리면 그만큼 DB 커넥션을 동시에 쥔다.
     *
     * <p><b>큐가 차면 부른 쪽이 직접 돌린다</b>({@code CallerRunsPolicy}). 버리면 그 후보는 결과를 반영받지 못한 채 {@code
     * DISPATCHED}로 남고 깨울 이벤트가 다시 오지 않아 묶음이 영영 끝나지 않는다 — 느려지는 편이 낫다.
     *
     * <p>종료할 때는 넣어 둔 일을 마치고 내려간다. 같은 이유다.
     */
    @Bean(destroyMethod = "shutdown")
    TaskExecutor paramSearchProgressExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1_000);
        executor.setThreadNamePrefix("param-search-progress-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
