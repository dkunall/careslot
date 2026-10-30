package com.careslot.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Hexagonal rules, checked against the compiled main classes so a violation fails the build, not a review. */
class ArchitectureTest {

    private static JavaClasses main;

    @BeforeAll
    static void load() {
        main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.careslot");
    }

    @Test
    void domainIsPlainJava() {
        classes().that().resideInAPackage("..domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..domain..")
                .because("domain rules must be testable without Spring, a database or a network")
                .check(main);
    }

    @Test
    void applicationNeverReachesIntoAdapters() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..adapter..")
                .because("application code talks to ports; adapters implement them")
                .check(main);
    }

    @Test
    void adaptersArePrivateToTheirModule() {
        for (String module : new String[]{"directory", "scheduling", "queue", "prescribing", "notify", "interop"}) {
            noClasses().that().resideOutsideOfPackage("com.careslot." + module + "..")
                    .should().dependOnClassesThat().resideInAPackage("com.careslot." + module + ".adapter..")
                    .because("other modules use " + module + " through its application services")
                    .check(main);
        }
    }

    @Test
    void webAdaptersDoNotTouchPersistence() {
        noClasses().that().resideInAPackage("..adapter.web..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter.persistence..", "org.springframework.jdbc..")
                .check(main);
    }

    @Test
    void sharedDependsOnNoBusinessModule() {
        noClasses().that().resideInAPackage("com.careslot.shared..")
                .should().dependOnClassesThat().resideInAnyPackage("com.careslot.directory..",
                        "com.careslot.scheduling..", "com.careslot.queue..", "com.careslot.prescribing..",
                        "com.careslot.notify..", "com.careslot.interop..")
                .check(main);
    }

    @Test
    void modulesHaveNoCycles() {
        slices().matching("com.careslot.(*)..").should().beFreeOfCycles().check(main);
    }

    @Test
    void noFieldInjection() {
        noFields().should().beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
                .because("constructor injection keeps dependencies visible and classes testable")
                .check(main);
    }
}
