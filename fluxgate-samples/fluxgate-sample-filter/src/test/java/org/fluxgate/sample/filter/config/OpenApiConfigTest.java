package org.fluxgate.sample.filter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.swagger.v3.oas.models.info.Info;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

/** The OpenAPI version is the Maven project version, read from the generated build-info. */
class OpenApiConfigTest {

  @Test
  void versionIsTheProjectVersionFromBuildInfo() throws IOException {
    Properties buildInfo = new Properties();
    try (InputStream in = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
      assertNotNull(in, "spring-boot-maven-plugin must run the build-info goal");
      buildInfo.load(in);
    }
    String projectVersion = buildInfo.getProperty("build.version");
    assertNotNull(projectVersion);
    assertFalse(projectVersion.isBlank());

    Properties entries = new Properties();
    buildInfo.stringPropertyNames().stream()
        .filter(key -> key.startsWith("build."))
        .forEach(key -> entries.setProperty(key.substring(6), buildInfo.getProperty(key)));
    StaticListableBeanFactory beans =
        new StaticListableBeanFactory(Map.of("buildProperties", new BuildProperties(entries)));

    String version =
        new OpenApiConfig()
            .customOpenAPI(beans.getBeanProvider(BuildProperties.class))
            .getInfo()
            .getVersion();

    assertEquals(projectVersion, version);
  }

  @Test
  void versionIsUnknownWithoutBuildInfo() {
    StaticListableBeanFactory none = new StaticListableBeanFactory();

    String version =
        new OpenApiConfig()
            .customOpenAPI(none.getBeanProvider(BuildProperties.class))
            .getInfo()
            .getVersion();

    assertEquals("unknown", version);
  }

  @Test
  void contactAndLicenseMatchTheProject() {
    StaticListableBeanFactory none = new StaticListableBeanFactory();

    Info info =
        new OpenApiConfig().customOpenAPI(none.getBeanProvider(BuildProperties.class)).getInfo();

    assertEquals("https://github.com/OpenFluxGate/fluxgate", info.getContact().getUrl());
    assertEquals("MIT", info.getLicense().getName());
    assertEquals("https://opensource.org/licenses/MIT", info.getLicense().getUrl());
  }
}
