package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.*;
import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.Test;

public class SandwichLookaroundTest {

  @Test
  void lookbehind_lookahead_find() {
    ReggieMatcher m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertTrue(m.find("[value]"), "find should return true for '[value]'");
  }

  @Test
  void lookbehind_lookahead_findMatch() {
    ReggieMatcher m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertNotNull(m.findMatch("[hello]"), "findMatch should return non-null");
    assertEquals("hello", m.findMatch("[hello]").group(0));
  }

  @Test
  void lookbehind_lookahead_multipleInString() {
    ReggieMatcher m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertNotNull(m.findMatch("[one][two]"), "should find first match");
    assertEquals("one", m.findMatch("[one][two]").group(0));
  }

  @Test
  void lookahead_only_find_still_works() {
    ReggieMatcher m = Reggie.compile("foo(?=bar)");
    assertTrue(m.find("foobar"), "lookahead-only find must still work");
    assertFalse(m.find("foobaz"), "lookahead-only find must reject non-match");
  }
}
