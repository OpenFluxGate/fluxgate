package fixture;

import java.util.Map;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.sample.redis.RedisSampleApplication;
import org.fluxgate.sample.redis.config.DynamicRuleSetProvider;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Configuration
public class FilterFixture {
  public static void main(String[] args) {
    SpringApplication.run(new Class<?>[] {RedisSampleApplication.class, FilterFixture.class}, args);
  }

  @Bean
  public FluxgateRateLimitFilter benchmarkFilter(RateLimiter limiter, DynamicRuleSetProvider provider) {
    return new FluxgateRateLimitFilter((context, ruleSetId) -> {
      var rule = provider.findById(ruleSetId).orElseThrow();
      var result = limiter.tryConsume(context, rule, 1);
      return result.isAllowed()
          ? RateLimitResponse.allowed(result.getRemainingTokens(), result.getNanosToWaitForRefill() / 1_000_000)
          : RateLimitResponse.rejected(result.getNanosToWaitForRefill() / 1_000_000);
    }, "api-limits", new String[] {"/benchmark/api/test"}, new String[0]);
  }

  @RestController
  public static class Endpoint {
    @GetMapping("/benchmark/api/test")
    public Map<String, String> test() {
      return Map.of("status", "ok");
    }
  }
}
