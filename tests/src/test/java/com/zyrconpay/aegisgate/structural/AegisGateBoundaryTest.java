package com.zyrconpay.aegisgate.structural;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

public class AegisGateBoundaryTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.zyrconpay.aegisgate");

    @Test
    public void testModuleBoundaries() {
        // Enforce layered architecture or module boundaries
        ArchRule boundaryRule = noClasses()
                .that().resideInAPackage("..ingress..")
                .should().dependOnClassesThat().resideInAPackage("..orchestrator..");

        boundaryRule.check(classes);
    }

    @Test
    public void testDirectPaymentIsolation() {
        // The direct payment package is physically and logically forbidden from importing or interacting with the orchestrator
        ArchRule isolationRule = noClasses()
                .that().resideInAPackage("..directpayment..")
                .should().dependOnClassesThat().resideInAPackage("..orchestrator..");

        isolationRule.allowEmptyShould(true).check(classes);
    }

    @Test
    public void testIngressDatabaseIsolation() {
        // Ingress module should not depend on JPA or relational database classes (no DB connection allowed)
        ArchRule noDatabaseRule = noClasses()
                .that().resideInAPackage("..ingress..")
                .should().dependOnClassesThat().resideInAPackage("..jpa..")
                .orShould().dependOnClassesThat().resideInAPackage("..jdbc..")
                .orShould().dependOnClassesThat().resideInAPackage("..hibernate..");

        noDatabaseRule.check(classes);
    }
}
