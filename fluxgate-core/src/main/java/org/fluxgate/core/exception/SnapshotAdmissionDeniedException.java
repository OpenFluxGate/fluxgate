package org.fluxgate.core.exception;

/** Explicit operator snapshot authorization is absent or could not be verified. Never retry. */
public final class SnapshotAdmissionDeniedException extends FluxgateException {
  public SnapshotAdmissionDeniedException() {
    super("Policy snapshot is not authorized by the migration admission gate");
  }

  public SnapshotAdmissionDeniedException(Throwable cause) {
    super("Policy snapshot authorization could not be verified", cause);
  }
}
