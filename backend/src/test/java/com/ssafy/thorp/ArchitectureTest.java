package com.ssafy.thorp;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 패키지 계층 의존 규칙 검증. 근거: docs/adr/0001-be-layered-package-architecture.md.
 *
 * <p>규칙은 패키지 이름 패턴(`..presentation..` 등) 기반이라 새 도메인 패키지를 추가하면 별도 등록 없이 자동으로 검사 대상이 된다.
 *
 * <p>테스트 클래스는 제외한다. 슬라이스 테스트가 계층 밖에 더미 컨트롤러를 두는 경우가 있어 프로덕션 코드만 검사한다.
 */
@AnalyzeClasses(packages = "com.ssafy.thorp", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.ssafy.thorp";
    private static final String COMMON = ROOT + ".common..";

    /** 규칙 1~3: presentation → application → domain, infrastructure → application/domain 만 허용. */
    @ArchTest
    static final ArchRule 계층_의존_방향 =
            layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Presentation")
                    .definedBy("..presentation..")
                    .layer("Application")
                    .definedBy("..application..")
                    .layer("Domain")
                    .definedBy("..domain..")
                    .layer("Infrastructure")
                    .definedBy("..infrastructure..")
                    .whereLayer("Presentation")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Infrastructure")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Application")
                    .mayOnlyBeAccessedByLayers("Presentation", "Infrastructure")
                    .whereLayer("Domain")
                    .mayOnlyBeAccessedByLayers("Presentation", "Application", "Infrastructure");

    /** 규칙 1(보강): 도메인 계층은 프레임워크에 의존하지 않는다. */
    @ArchTest
    static final ArchRule 도메인은_프레임워크에_의존하지_않는다 =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..application..",
                            "..infrastructure..",
                            "..presentation..",
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.fasterxml..");

    /**
     * 규칙 7: 브로커 메시지 DTO(application.port.message)는 순수 record다. 직렬화 규칙은 infrastructure의 메시지
     * ObjectMapper가 담당하므로 Jackson 애노테이션·Spring·JPA에 의존하지 않는다. 근거:
     * docs/adr/0002-rabbitmq-backtest-message-contract.md.
     */
    @ArchTest
    static final ArchRule 메시지_DTO는_프레임워크에_의존하지_않는다 =
            noClasses()
                    .that()
                    .resideInAPackage("..application.port.message..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..infrastructure..",
                            "..presentation..",
                            "org.springframework..",
                            "jakarta..",
                            "com.fasterxml..");

    /** 규칙 4: common은 어떤 도메인 패키지도 참조하지 않는다. */
    @ArchTest
    static final ArchRule common은_도메인을_참조하지_않는다 =
            noClasses()
                    .that()
                    .resideInAPackage(COMMON)
                    .should()
                    .dependOnClassesThat(
                            resideInAPackage(ROOT + "..").and(resideOutsideOfPackage(COMMON)));

    /** 규칙 5: JPA Entity는 infrastructure.persistence 에만 둔다. */
    @ArchTest
    static final ArchRule 엔티티는_infrastructure_persistence에만_있다 =
            classes()
                    .that()
                    .areAnnotatedWith(Entity.class)
                    .should()
                    .resideInAPackage("..infrastructure.persistence..");

    /** 규칙 6: 컨트롤러는 presentation.controller 에만 둔다. */
    @ArchTest
    static final ArchRule 컨트롤러는_presentation_controller에만_있다 =
            classes()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .resideInAPackage("..presentation.controller..");
}
