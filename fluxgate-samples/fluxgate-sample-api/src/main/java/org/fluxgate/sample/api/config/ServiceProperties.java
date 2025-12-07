package org.fluxgate.sample.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fluxgate.services")
public class ServiceProperties {

  private String controlPlaneUrl = "http://localhost:8081";
  private String dataPlaneUrl = "http://localhost:8082";

  public String getControlPlaneUrl() {
    return controlPlaneUrl;
  }

  public void setControlPlaneUrl(String controlPlaneUrl) {
    this.controlPlaneUrl = controlPlaneUrl;
  }

  public String getDataPlaneUrl() {
    return dataPlaneUrl;
  }

  public void setDataPlaneUrl(String dataPlaneUrl) {
    this.dataPlaneUrl = dataPlaneUrl;
  }
}
