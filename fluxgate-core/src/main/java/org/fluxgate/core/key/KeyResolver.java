package org.fluxgate.core.key;

import org.fluxgate.core.context.RequestContext;

public interface KeyResolver {
  RateLimitKey resolve(RequestContext context);
}
