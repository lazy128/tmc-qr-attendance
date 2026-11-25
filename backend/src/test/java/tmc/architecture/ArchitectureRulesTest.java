package tmc.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "tmc", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {
  @ArchTest
  static final ArchRule domainMustNotDependOnInfrastructureOrSpring = noClasses()
      .that().resideInAPackage("tmc..domain..")
      .should().dependOnClassesThat()
      .resideInAnyPackage("tmc..infrastructure..", "org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..");

  @ArchTest
  static final ArchRule applicationMustNotDependOnInfrastructureOrSpring = noClasses()
      .that().resideInAPackage("tmc..application..")
      .should().dependOnClassesThat()
      .resideInAnyPackage("tmc..infrastructure..", "org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..");

  @ArchTest
  static final ArchRule modulesMustNotHaveDependencyCycles = slices()
      .matching("tmc.(*)..")
      .should().beFreeOfCycles();

  @ArchTest
  static final ArchRule accessDoesNotImportOtherModuleInfrastructure = noClasses()
      .that().resideInAPackage("tmc.access..")
      .should().dependOnClassesThat()
      .resideInAnyPackage("tmc.attendance.infrastructure..", "tmc.userqr.infrastructure..");

  @ArchTest
  static final ArchRule attendanceDoesNotImportOtherModuleInfrastructure = noClasses()
      .that().resideInAPackage("tmc.attendance..")
      .should().dependOnClassesThat()
      .resideInAnyPackage("tmc.access.infrastructure..", "tmc.userqr.infrastructure..");

  @ArchTest
  static final ArchRule userQrDoesNotImportOtherModuleInfrastructure = noClasses()
      .that().resideInAPackage("tmc.userqr..")
      .should().dependOnClassesThat()
      .resideInAnyPackage("tmc.access.infrastructure..", "tmc.attendance.infrastructure..");
}
