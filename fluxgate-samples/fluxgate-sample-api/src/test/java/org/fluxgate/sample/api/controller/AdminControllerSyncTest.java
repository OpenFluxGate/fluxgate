package org.fluxgate.sample.api.controller;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/** {@code POST /admin/sync} against mocked Control-plane and Data-plane services. */
class AdminControllerSyncTest {

  private static final String RULES_URL =
      "http://control-plane/admin/rules?ruleSetId=api-gateway-rules";

  private MockRestServiceServer controlPlane;
  private MockRestServiceServer dataPlane;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    RestClient.Builder controlBuilder = RestClient.builder().baseUrl("http://control-plane");
    RestClient.Builder dataBuilder = RestClient.builder().baseUrl("http://data-plane");
    controlPlane = MockRestServiceServer.bindTo(controlBuilder).build();
    dataPlane = MockRestServiceServer.bindTo(dataBuilder).build();
    AdminController controller = new AdminController(controlBuilder.build(), dataBuilder.build());
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  private void controlPlaneReturns(String bandJson) {
    controlPlane
        .expect(requestTo(RULES_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "[{\"id\":\"r1\",\"ruleSetIdOrNull\":\"api-gateway-rules\",\"bands\":["
                    + bandJson
                    + "]}]",
                MediaType.APPLICATION_JSON));
  }

  @Test
  void zeroWindow_isRejectedWith422() throws Exception {
    controlPlaneReturns("{\"window\":\"PT0S\",\"capacity\":10}");

    mockMvc
        .perform(post("/admin/sync").param("ruleSetId", "api-gateway-rules"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.ruleSetId").value("api-gateway-rules"));

    controlPlane.verify();
    dataPlane.verify(); // nothing was registered
  }

  @Test
  void zeroCapacity_isRejectedWith422() throws Exception {
    controlPlaneReturns("{\"window\":\"PT1M\",\"capacity\":0}");

    mockMvc
        .perform(post("/admin/sync").param("ruleSetId", "api-gateway-rules"))
        .andExpect(status().isUnprocessableEntity());

    dataPlane.verify();
  }

  @Test
  void missingCapacity_isRejectedWith422() throws Exception {
    controlPlaneReturns("{\"window\":\"PT1M\"}");

    mockMvc
        .perform(post("/admin/sync").param("ruleSetId", "api-gateway-rules"))
        .andExpect(status().isUnprocessableEntity());

    dataPlane.verify();
  }

  @Test
  void subSecondIsoWindow_isRoundedUpToOneSecond() throws Exception {
    controlPlaneReturns("{\"window\":\"PT0.5S\",\"capacity\":5}");
    dataPlane
        .expect(requestTo("http://data-plane/admin/rules"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    "{\"ruleSetId\":\"api-gateway-rules\",\"capacity\":5,\"windowSeconds\":1}",
                    true))
        .andRespond(
            withSuccess("{\"ruleSetId\":\"api-gateway-rules\"}", MediaType.APPLICATION_JSON));

    mockMvc
        .perform(post("/admin/sync").param("ruleSetId", "api-gateway-rules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windowSeconds").value(1))
        .andExpect(jsonPath("$.capacity").value(5));

    dataPlane.verify();
  }
}
