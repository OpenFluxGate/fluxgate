package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.FileCopyUtils;

/** Unit tests for {@link PrincipalIdentityResolver}. */
class PrincipalIdentityResolverTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldSeeSpringSecurityOnThisClasspath() {
    assertThat(PrincipalIdentityResolver.isAvailable()).isTrue();
  }

  @Test
  void shouldReturnNullWhenNothingIsAuthenticated() {
    assertThat(PrincipalIdentityResolver.currentUserId()).isNull();
  }

  @Test
  void shouldReturnThePrincipalName() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken("alice", "credentials", AuthorityUtils.NO_AUTHORITIES));

    assertThat(PrincipalIdentityResolver.currentUserId()).isEqualTo("alice");
  }

  @Test
  void shouldReturnNullForABlankPrincipalName() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken("  ", "credentials", AuthorityUtils.NO_AUTHORITIES));

    assertThat(PrincipalIdentityResolver.currentUserId()).isNull();
  }

  @Test
  void shouldLoadAndWorkWithoutSpringSecurityOnTheClasspath() throws Exception {
    // spring-security-core is a provided dependency, so a deployment without it must still be able
    // to load this class: the Spring Security calls live in a nested class that is only reached
    // once isAvailable() has confirmed the types exist. A plain FilteredClassLoader cannot show
    // this, because it delegates the resolver itself to the parent where Spring Security is
    // present, so the resolver is redefined here by a loader that really cannot see it.
    try (SecurityFreeClassLoader loader = new SecurityFreeClassLoader()) {
      Class<?> resolver = loader.loadClass(PrincipalIdentityResolver.class.getName());
      assertThat(resolver.getClassLoader()).isSameAs(loader);

      Method isAvailable = resolver.getMethod("isAvailable");
      Method currentUserId = resolver.getMethod("currentUserId");

      assertThat(isAvailable.invoke(null)).isEqualTo(false);
      assertThat(currentUserId.invoke(null)).isNull();
    }
  }

  /**
   * Defines {@code org.fluxgate.spring.filter} classes itself and hides Spring Security from them,
   * so the classpath guard is exercised the way a deployment without spring-security-core would.
   */
  private static final class SecurityFreeClassLoader extends ClassLoader implements AutoCloseable {

    private SecurityFreeClassLoader() {
      super(PrincipalIdentityResolverTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("org.springframework.security.")) {
        throw new ClassNotFoundException(name);
      }
      if (!name.startsWith("org.fluxgate.spring.filter.")) {
        return super.loadClass(name, resolve);
      }
      Class<?> loaded = findLoadedClass(name);
      if (loaded == null) {
        loaded = defineFromParentResource(name);
      }
      if (resolve) {
        resolveClass(loaded);
      }
      return loaded;
    }

    private Class<?> defineFromParentResource(String name) throws ClassNotFoundException {
      String resource = name.replace('.', '/') + ".class";
      try (InputStream in = getParent().getResourceAsStream(resource)) {
        if (in == null) {
          throw new ClassNotFoundException(name);
        }
        byte[] bytes = FileCopyUtils.copyToByteArray(in);
        return defineClass(name, bytes, 0, bytes.length);
      } catch (IOException e) {
        throw new ClassNotFoundException(name, e);
      }
    }

    @Override
    public void close() {
      // Nothing to release; implemented so the loader can be used in a try-with-resources block.
    }
  }
}
