package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
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
 * Tests that both auto-configuration registration files of this starter stay in sync.
 *
 * <p>This starter requires Spring Boot 2.7+ ({@code @AutoConfiguration} is a 2.7 API), so the
 * authoritative registration is {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}. {@code
 * META-INF/spring.factories} is kept for tooling compatibility only - IDE inspections and
 * documentation generators still read it - and does not make the starter work on Boot 2.0 - 2.6.
 * The two files must still list the same classes so that tooling describes what Boot loads.
 *
 * <p>The files are read from {@code src/main/resources} rather than from the classpath: both
 * resource paths also exist in Spring's own jars, so a classpath lookup would be ambiguous.
 *
 * <p>An {@link AutoConfiguration @AutoConfiguration} class missing from both files is silently
 * never loaded, so the imports file must also list exactly the annotated classes of this package.
 */
class AutoConfigurationRegistrationTest {

  private static final Path RESOURCES = Paths.get("src", "main", "resources");

  private static final Path IMPORTS_FILE =
      RESOURCES.resolve(
          Paths.get(
              "META-INF",
              "spring",
              "org.springframework.boot.autoconfigure.AutoConfiguration.imports"));

  private static final Path FACTORIES_FILE =
      RESOURCES.resolve(Paths.get("META-INF", "spring.factories"));

  private static final String ENABLE_AUTO_CONFIGURATION_KEY =
      "org.springframework.boot.autoconfigure.EnableAutoConfiguration";

  @Test
  void shouldRegisterTheSameClassesInBothFiles() {
    assertThat(readImports())
        .as("spring.factories must list exactly the classes AutoConfiguration.imports lists")
        .containsExactlyInAnyOrderElementsOf(readFactories());
  }

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

  private static List<String> readFactories() {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(FACTORIES_FILE)) {
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + FACTORIES_FILE.toAbsolutePath(), e);
    }

    String value = properties.getProperty(ENABLE_AUTO_CONFIGURATION_KEY, "");
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .collect(Collectors.toList());
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
