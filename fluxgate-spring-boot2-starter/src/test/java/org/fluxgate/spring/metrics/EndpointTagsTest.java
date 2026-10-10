package org.fluxgate.spring.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Tests for {@link EndpointTags}. */
class EndpointTagsTest {

  @Test
  void foldsEveryValuePastTheCapIntoOther() {
    EndpointTags tags = new EndpointTags(true, false, 2);

    assertThat(tags.tag("/a")).isEqualTo("/a");
    assertThat(tags.tag("/b")).isEqualTo("/b");
    assertThat(tags.tag("/c")).isEqualTo(EndpointTags.OTHER_ENDPOINT);
    assertThat(tags.tag("/a")).as("known values keep their tag").isEqualTo("/a");
  }

  @Test
  void aValueAdmittedWhileAnotherWaitsForTheLockStillCountsAgainstTheCap() {
    // Reproduces the race deterministically: the first call passes the unlocked budget check, and
    // before it takes the lock another call admits the last free value.
    AtomicReference<EndpointTags> self = new AtomicReference<>();
    AtomicBoolean raced = new AtomicBoolean();
    AtomicReference<String> racingTag = new AtomicReference<>();
    EndpointTags tags =
        new EndpointTags(
            true,
            false,
            1,
            () -> {
              if (raced.compareAndSet(false, true)) {
                racingTag.set(self.get().tag("/b"));
              }
            });
    self.set(tags);

    assertThat(tags.tag("/a")).isEqualTo(EndpointTags.OTHER_ENDPOINT);
    assertThat(racingTag).hasValue("/b");
    assertThat(tags.tag("/b")).isEqualTo("/b");
    assertThat(tags.tag("/a")).as("the budget stayed full").isEqualTo(EndpointTags.OTHER_ENDPOINT);
  }

  @Test
  void theSameValueAdmittedWhileItWaitsForTheLockKeepsItsTagWhenThatFillsTheBudget() {
    // The racing call admits the very value the first call is about to admit, and that fills the
    // budget. The first call must see the value as known under the lock rather than as over budget.
    AtomicReference<EndpointTags> self = new AtomicReference<>();
    AtomicBoolean raced = new AtomicBoolean();
    AtomicReference<String> racingTag = new AtomicReference<>();
    EndpointTags tags =
        new EndpointTags(
            true,
            false,
            1,
            () -> {
              if (raced.compareAndSet(false, true)) {
                racingTag.set(self.get().tag("/a"));
              }
            });
    self.set(tags);

    assertThat(tags.tag("/a")).isEqualTo("/a");
    assertThat(racingTag).hasValue("/a");
    assertThat(tags.tag("/b")).as("the budget is full").isEqualTo(EndpointTags.OTHER_ENDPOINT);
  }
}
