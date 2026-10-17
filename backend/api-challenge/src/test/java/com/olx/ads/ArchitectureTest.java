package com.olx.ads;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the dependency rule of the hexagonal architecture (DECISIONS.md, section 8). */
@AnalyzeClasses(packages = "com.olx.ads", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainDependsOnlyOnTheJdk =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideOutsideOfPackages("..domain..", "java..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule applicationDoesNotDependOnAdapters =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule inboundAndOutboundAdaptersAreIndependent =
      noClasses()
          .that()
          .resideInAPackage("..adapter.in..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter.out..")
          .allowEmptyShould(true);

  /** DTOs and web errors are public only to cross the web subpackages, not to leave the adapter. */
  @ArchTest
  static final ArchRule webTypesStayInTheWebAdapter =
      classes()
          .that()
          .resideInAPackage("..adapter.in.web..")
          .should()
          .onlyHaveDependentClassesThat()
          .resideInAPackage("..adapter.in.web..");
}
