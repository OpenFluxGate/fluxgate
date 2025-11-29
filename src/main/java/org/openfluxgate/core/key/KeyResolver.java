package org.openfluxgate.core.key;

import org.openfluxgate.core.context.RequestContext;

public interface KeyResolver {
    RateLimitKey resolve(RequestContext context);
}