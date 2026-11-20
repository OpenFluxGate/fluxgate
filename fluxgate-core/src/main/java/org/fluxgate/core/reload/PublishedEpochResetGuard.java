package org.fluxgate.core.reload;

/** Blocks destructive reset APIs when counters are owned by published policy epochs. */
public final class PublishedEpochResetGuard implements BucketResetHandler {
  @Override
  public void resetBuckets(String ruleSetId) {
    throw unsupported();
  }

  @Override
  public void resetAllBuckets() {
    throw unsupported();
  }

  private UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException(
        "PUBLISHED_EPOCH forbids destructive bucket reset; publish an explicit policy reset instead");
  }
}
