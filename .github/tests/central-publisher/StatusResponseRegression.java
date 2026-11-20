import com.fasterxml.jackson.databind.ObjectMapper;
import org.sonatype.central.publisher.client.httpclient.StatusPublisherEndpoint;
import org.sonatype.central.publisher.client.model.DeploymentApiResponse;

/** Uses the plugin's actual status parser, without making HTTP requests. */
class StatusResponseRegression {
  public static void main(String[] args) throws Exception {
    var field = StatusPublisherEndpoint.class.getDeclaredField("objectMapper");
    field.setAccessible(true);
    var mapper = (ObjectMapper) field.get(null);
    for (String state : new String[] {"PUBLISHED", "FAILED", "VALIDATING"}) {
      String json = "{\"deploymentId\":\"local-test\",\"deploymentState\":\"" + state
          + "\",\"warnings\":[\"synthetic quota notice\"],\"futureField\":true,"
          + "\"errors\":{\"local-test\":[\"synthetic validation error\"]}}";
      var response = mapper.readValue(json, DeploymentApiResponse.class);
      if (!response.getDeploymentState().name().equals(state)
          || response.getWarnings().size() != 1 || response.getErrors().size() != 1) {
        throw new AssertionError("Status/warnings/errors must remain intact");
      }
    }
    try {
      mapper.readValue("{\"deploymentState\":\"NOT_A_REAL_STATE\"}", DeploymentApiResponse.class);
      throw new AssertionError("Unknown deployment state was accepted");
    } catch (com.fasterxml.jackson.databind.JsonMappingException expected) { }
    System.out.println("PASS: actual plugin parser accepts warnings without hiding failed states/errors");
  }
}
