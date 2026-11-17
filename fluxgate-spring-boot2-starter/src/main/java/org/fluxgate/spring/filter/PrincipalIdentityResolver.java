package org.fluxgate.spring.filter;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Reads the caller's user id from the Spring Security context, when Spring Security is present.
 *
 * <p>C-4: this is the piece that turns {@code PER_USER} limiting from "whatever the client put in
 * {@code X-User-Id}" into "the principal the security stack authenticated". It is the library
 * provided counterpart to the customizer {@code SECURITY.md} used to tell every user to write.
 *
 * <p>Spring Security is optional, so the classes are only touched from {@link
 * SpringSecurityAccess}, a nested class the JVM loads on first use and only after {@link
 * #isAvailable()} has said the classes exist. No reflection is involved: with Spring Security
 * absent, this class still loads and {@link #currentUserId()} returns null.
 *
 * <p>Only the user id is resolved. An API key is a credential the principal does not generally
 * expose, so {@code IdentitySource.PRINCIPAL} simply has no API key.
 */
public final class PrincipalIdentityResolver {

  /** Looked up by name so spring-security-core stays an optional dependency. */
  private static final String SECURITY_CONTEXT_HOLDER_CLASS =
      "org.springframework.security.core.context.SecurityContextHolder";

  private static final boolean AVAILABLE =
      ClassUtils.isPresent(
          SECURITY_CONTEXT_HOLDER_CLASS, PrincipalIdentityResolver.class.getClassLoader());

  private PrincipalIdentityResolver() {
    // Utility class
  }

  /**
   * Whether Spring Security is on the classpath, and a principal can therefore be read at all.
   *
   * @return true when {@code SecurityContextHolder} is available
   */
  public static boolean isAvailable() {
    return AVAILABLE;
  }

  /**
   * Returns the name of the currently authenticated principal.
   *
   * @return the principal name, or null when Spring Security is absent, nothing is authenticated,
   *     the authentication is anonymous, or the name is blank
   */
  public static String currentUserId() {
    if (!AVAILABLE) {
      return null;
    }
    return SpringSecurityAccess.currentUserId();
  }

  /**
   * Holder for the Spring Security calls.
   *
   * <p>Kept as a separate class file so its constant pool, which names Spring Security types, is
   * only resolved once {@link #AVAILABLE} has confirmed those types exist.
   */
  private static final class SpringSecurityAccess {

    private SpringSecurityAccess() {
      // Utility class
    }

    static String currentUserId() {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null
          || !authentication.isAuthenticated()
          || authentication instanceof AnonymousAuthenticationToken) {
        return null;
      }
      String name = authentication.getName();
      return StringUtils.hasText(name) ? name : null;
    }
  }
}
