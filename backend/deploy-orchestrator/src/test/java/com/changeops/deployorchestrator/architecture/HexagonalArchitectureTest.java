package com.changeops.deployorchestrator.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@DisplayName("Arquitetura Hexagonal - Regras de Dependência")
class HexagonalArchitectureTest {

    private static final String ROOT_PACKAGE = "com.changeops.deployorchestrator";
    
    // CORREÇÃO 1: Usar ".." apenas no final, nunca concatenar com outro ".."
    private static final String DOMAIN_PACKAGE = ROOT_PACKAGE + ".domain";
    private static final String APPLICATION_PACKAGE = ROOT_PACKAGE + ".application";
    private static final String INFRASTRUCTURE_PACKAGE = ROOT_PACKAGE + ".infrastructure";

    // Importar apenas classes de produção, ignorar testes e código gerado
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages(ROOT_PACKAGE);

    @Test
    @DisplayName("Domínio não deve depender de infraestrutura")
    void domainShouldNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(DOMAIN_PACKAGE + "..")
                .should().dependOnClassesThat()
                .resideInAPackage(INFRASTRUCTURE_PACKAGE + "..");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Domínio deve depender apenas de si mesmo e bibliotecas padrão")
    void domainShouldOnlyDependOnItselfAndStandardLibs() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(DOMAIN_PACKAGE + "..")
                .should().dependOnClassesThat()
                .resideOutsideOfPackages(
                        DOMAIN_PACKAGE + "..",
                        "java..",
                        "javax..",
                        "jakarta..",
                        "org.slf4j..",
                        "com.fasterxml.jackson..",
                        "lombok..");  // CORREÇÃO 2: Ignorar dependências do Lombok

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Serviços de aplicação não devem depender de infraestrutura")
    void applicationServicesShouldNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(APPLICATION_PACKAGE + ".service..")
                .should().dependOnClassesThat()
                .resideInAPackage(INFRASTRUCTURE_PACKAGE + "..");

        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Infraestrutura deve depender apenas de ports e domínio")
    void infrastructureShouldOnlyDependOnPortsAndDomain() {
        // CORREÇÃO 3: Usar resideInAnyPackage com padrões válidos (sem concatenação de "..")
        ArchRule rule = classes()
                .that().resideInAPackage(INFRASTRUCTURE_PACKAGE + "..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        INFRASTRUCTURE_PACKAGE + "..",
                        APPLICATION_PACKAGE + ".port..",
                        DOMAIN_PACKAGE + "..",
                        "java..",
                        "javax..",
                        "jakarta..",
                        "org.springframework..",
                        "org.apache.kafka..",
                        "com.fasterxml.jackson..",
                        "io.micrometer..",
                        "lombok..");  // Permitir Lombok em qualquer camada

        rule.check(PRODUCTION_CLASSES);
    }
}