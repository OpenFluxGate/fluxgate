package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import org.junit.jupiter.api.Test;

/**
 * R15: the starter is compiled with {@code -parameters}.
 *
 * <p>Spring Framework 6.1 dropped the LocalVariableTable fallback, so without real parameter names
 * every by-name resolution of same-type beans fails on Boot 3 while still working on Boot 2.
 */
class CompilerParametersTest {

  @Test
  void beanMethodParameterNamesAreRetained() throws Exception {
    Method method =
        FluxgateMongoAutoConfiguration.class.getMethod(
            "fluxgateMongoDatabase", FluxgateMongoClientHolder.class);
    Parameter parameter = method.getParameters()[0];
    assertThat(parameter.isNamePresent()).isTrue();
    assertThat(parameter.getName()).isEqualTo("fluxgateMongoClientHolder");
  }
}
