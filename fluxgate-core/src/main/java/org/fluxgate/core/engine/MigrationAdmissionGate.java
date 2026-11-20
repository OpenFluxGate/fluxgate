package org.fluxgate.core.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import org.fluxgate.core.exception.SnapshotAdmissionDeniedException;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;

/** Immutable, opt-in operator authorization. Not proof of writer fencing or debt migration. */
public final class MigrationAdmissionGate {
  private static final long MAX_REVISION = 9_007_199_254_740_991L;
  private static final MigrationAdmissionGate DISABLED =
      new MigrationAdmissionGate(false, Map.of());
  private final boolean enabled;
  private final Map<String, Approval> approvals;

  public static final class Approval {
    private final long revision;
    private final String epoch;

    public Approval(long revision, String epoch) {
      if (revision <= 0 || revision > MAX_REVISION || !validText(epoch) || "legacy".equals(epoch)) {
        throw new IllegalArgumentException(
            "Approval requires a positive exact revision and nonlegacy epoch");
      }
      this.revision = revision;
      this.epoch = epoch;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Approval)) return false;
      Approval a = (Approval) other;
      return revision == a.revision && epoch.equals(a.epoch);
    }

    @Override
    public int hashCode() {
      return Objects.hash(revision, epoch);
    }
  }

  private MigrationAdmissionGate(boolean enabled, Map<String, Approval> approvals) {
    Objects.requireNonNull(approvals);
    approvals.forEach(
        (id, approval) -> {
          if (!validText(id) || approval == null)
            throw new IllegalArgumentException("Invalid snapshot approval");
        });
    this.enabled = enabled;
    this.approvals = Map.copyOf(approvals);
  }

  public static MigrationAdmissionGate disabled() {
    return DISABLED;
  }

  public static MigrationAdmissionGate enabled(Map<String, Approval> approvals) {
    return new MigrationAdmissionGate(true, approvals);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void requireRequest(String id) {
    if (enabled && !approvals.containsKey(id)) throw new SnapshotAdmissionDeniedException();
  }

  public void requireMissingAllowed() {
    if (enabled) throw new SnapshotAdmissionDeniedException();
  }

  public void requireSnapshot(RateLimitRuleSet snapshot) {
    if (!enabled) return;
    Approval approval = approvals.get(snapshot.getId());
    if (approval == null || snapshot.getRules().isEmpty())
      throw new SnapshotAdmissionDeniedException();
    for (org.fluxgate.core.config.RateLimitRule rule : snapshot.getRules()) {
      Object epoch = rule.getAttributes().get("fluxgate.counterEpoch");
      Object revision = rule.getAttributes().get("fluxgate.counterRevision");
      if (!approval.epoch.equals(epoch) || exactRevision(revision) != approval.revision) {
        throw new SnapshotAdmissionDeniedException();
      }
    }
  }

  private static long exactRevision(Object value) {
    if (!(value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger
        || value instanceof BigDecimal
        || value instanceof Float
        || value instanceof Double)) return -1;
    try {
      long result = new BigDecimal(value.toString()).longValueExact();
      return result > 0 && result <= MAX_REVISION ? result : -1;
    } catch (NumberFormatException | ArithmeticException e) {
      return -1;
    }
  }

  private static boolean validText(String s) {
    return s != null
        && !s.isEmpty()
        && s.codePoints()
            .noneMatch(
                c ->
                    Character.isWhitespace(c)
                        || Character.isSpaceChar(c)
                        || Character.isISOControl(c));
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof MigrationAdmissionGate)) return false;
    MigrationAdmissionGate g = (MigrationAdmissionGate) other;
    return enabled == g.enabled && approvals.equals(g.approvals);
  }

  @Override
  public int hashCode() {
    return Objects.hash(enabled, approvals);
  }
}
