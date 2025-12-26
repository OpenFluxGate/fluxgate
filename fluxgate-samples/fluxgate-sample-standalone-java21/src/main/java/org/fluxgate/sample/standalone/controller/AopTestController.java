package org.fluxgate.sample.standalone.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.fluxgate.spring.annotation.RateLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test/aop")
@Tag(name = "Test", description = "Rate-limited AOP-Test API")
public class AopTestController {

  @RateLimit(ruleSetId = "standalone-rules")
  @GetMapping("/aop-test1")
  @Operation(
      summary = "Standard rate-limited endpoint",
      description =
          "Rate-limited to 10 requests per minute per IP. " + "Uses 'standalone-rules' rule set.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Request allowed"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
  })
  public Object aopTest1() {
    return "SUCCESS";
  }
}
