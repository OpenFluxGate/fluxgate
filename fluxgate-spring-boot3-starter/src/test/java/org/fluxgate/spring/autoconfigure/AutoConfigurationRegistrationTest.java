package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;

/**
 * Tests the auto-configuration registration file of this starter.
 *
 * <p>Spring Boot 3 reads auto-configurations only from {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}. An {@link
 * AutoConfiguration @AutoConfiguration} class missing from that file is silently never loaded, so
 * the file must list exactly the annotated classes of this package.
 *
 * <p>The file is read from {@code src/main/resources} rather than from the classpath: the resource
 * path also exists in Spring Boot's own jars, so a classpath lookup would be ambiguous.
 */
class AutoConfigurationRegistrationTest {

  private static final Path IMPORTS_FILE =
      Paths.get(
          "src",
          "main",
          "resources",
          "META-INF",
          "spring",
          "org.springframework.boot.autoconfigure.AutoConfiguration.imports");

  @Test
  void shouldRegisterEveryAutoConfigurationClassOfThePackage() {
    assertThat(readImports())
        .as("AutoConfiguration.imports must list exactly the @AutoConfiguration classes")
        .containsExactlyInAnyOrderElementsOf(annotatedAutoConfigurations());
  }

  @Test
  void shouldRegisterAllNineAutoConfigurations() {
    assertThat(readImports())
        .hasSize(9)
        .doesNotHaveDuplicates()
        .contains(FluxgateAopExceptionHandlerAutoConfiguration.class.getName());
  }

  @Test
  void shouldRegisterOnlyClassesThatExist() {
    for (String className : readImports()) {
      assertThat(classIsPresent(className)).as("%s must be on the classpath", className).isTrue();
    }
  }

  private static List<String> readImports() {
    try {
      return Files.readAllLines(IMPORTS_FILE, StandardCharsets.UTF_8).stream()
          .map(String::trim)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + IMPORTS_FILE.toAbsolutePath(), e);
    }
  }

  /**
   * The classes of this package annotated with {@code @AutoConfiguration}, read from their class
   * files: a component scan would drop those whose conditions do not match in the test.
   */
  private static Set<String> annotatedAutoConfigurations() {
    String pattern =
        ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
            + ClassUtils.convertClassNameToResourcePath(
                AutoConfigurationRegistrationTest.class.getPackage().getName())
            + "/*.class";
    MetadataReaderFactory readers = new CachingMetadataReaderFactory();
    Set<String> classNames = new HashSet<>();
    try {
      for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
        AnnotationMetadata metadata = readers.getMetadataReader(resource).getAnnotationMetadata();
        if (metadata.hasAnnotation(AutoConfiguration.class.getName())) {
          classNames.add(metadata.getClassName());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot scan " + pattern, e);
    }
    assertThat(classNames).as("the scan must find the auto-configurations").isNotEmpty();
    return classNames;
  }

  private static boolean classIsPresent(String className) {
    try {
      Class.forName(className, false, AutoConfigurationRegistrationTest.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }
}
