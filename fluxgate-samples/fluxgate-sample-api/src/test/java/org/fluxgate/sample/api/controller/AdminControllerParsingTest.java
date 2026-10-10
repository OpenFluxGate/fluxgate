package org.fluxgate.sample.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminControllerParsingTest {

  @Test
  void windowAsIntegerSeconds() {
    assertEquals(60L, AdminController.parseWindowSeconds(Map.of("window", 60)));
  }

  @Test
  void windowAsDecimalSeconds() {
    assertEquals(60L, AdminController.parseWindowSeconds(Map.of("window", 60.000000000d)));
    assertEquals(61L, AdminController.parseWindowSeconds(Map.of("window", 60.5d)));
  }

  @Test
  void windowAsIsoString() {
    assertEquals(60L, AdminController.parseWindowSeconds(Map.of("window", "PT1M")));
  }

  @Test
  void fractionalIsoWindowIsRoundedUpLikeTheNumberPath() {
    assertEquals(1L, AdminController.parseWindowSeconds(Map.of("window", "PT0.5S")));
    assertEquals(61L, AdminController.parseWindowSeconds(Map.of("window", "PT60.5S")));
    assertEquals(
        AdminController.parseWindowSeconds(Map.of("window", 60.5d)),
        AdminController.parseWindowSeconds(Map.of("window", "PT60.5S")));
  }

  @Test
  void legacyWindowSecondsFallback() {
    assertEquals(30L, AdminController.parseWindowSeconds(Map.of("windowSeconds", 30)));
  }

  @Test
  void missingOrInvalidWindow() {
    assertNull(AdminController.parseWindowSeconds(Map.of()));
    assertNull(AdminController.parseWindowSeconds(Map.of("window", "bogus")));
  }

  @Test
  void capacity() {
    assertEquals(10L, AdminController.parseCapacity(10));
    assertNull(AdminController.parseCapacity(null));
  }
}
