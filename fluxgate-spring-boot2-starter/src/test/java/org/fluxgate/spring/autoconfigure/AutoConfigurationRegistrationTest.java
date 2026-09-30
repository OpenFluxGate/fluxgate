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
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tests that both auto-configuration registration files of this starter stay in sync.
 *
 * <p>Spring Boot 2.7+ reads {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}; Boot 2.0 - 2.6
 * reads only {@code META-INF/spring.factories}. A class listed in one file but not the other loads
 * on some Boot 2 versions and silently nowhere on the rest.
 *
 * <p>The files are read from {@code src/main/resources} rather than from the classpath: both
 * resource paths also exist in Spring's own jars, so a classpath lookup would be ambiguous.
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
  void shouldRegisterAllEightAutoConfigurations() {
    assertThat(readImports()).hasSize(8);
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

  private static boolean classIsPresent(String className) {
    try {
      Class.forName(className, false, AutoConfigurationRegistrationTest.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }
}
