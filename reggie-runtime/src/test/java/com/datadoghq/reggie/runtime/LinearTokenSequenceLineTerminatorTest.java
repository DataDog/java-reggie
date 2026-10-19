/*
 * Copyright 2026-Present Datadog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check for non-dotall {@code .} on the linear-token-sequence entry points ({@link
 * RuntimeCompiler#tryCompileFullCaptureLinearTokenSequence} and {@link
 * RuntimeCompiler#tryCompileNamedOnlyLinearTokenSequence}), which plain compilation does not reach.
 */
public class LinearTokenSequenceLineTerminatorTest {

  private static final String[] FULL_CAPTURE = {"(\\S+) .*", "(\\w+): .*"};

  private static final String[] NAMED_ONLY = {"(?<k>\\w+)=.*", "(?<a>\\S+) (?<b>\\S+) .*"};

  static List<String> inputs() {
    List<String> inputs = DotLineTerminatorTest.inputs();
    for (String t : new String[] {"\n", "\r", "\u0085", "\u2028", "\u2029", "\r\n"}) {
      inputs.add("a [x] bTc".replace("T", t));
      inputs.add("aT[x] b".replace("T", t));
      inputs.add("k: vTw".replace("T", t));
      inputs.add("k=vTw".replace("T", t));
      inputs.add("ab cdTe".replace("T", t));
    }
    return inputs;
  }

  @Test
  void fullCaptureAgreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    for (String p : FULL_CAPTURE) {
      LinearTokenSequenceMatcher m =
          RuntimeCompiler.tryCompileFullCaptureLinearTokenSequence(p, 0).matcher();
      assertNotNull(m, p + " must be admitted to keep covering the LTS path");
      Pattern j = Pattern.compile(p);
      for (String in : inputs()) {
        String diff = DotLineTerminatorTest.diff(m, j, in);
        if (diff != null) {
          mismatches.add("/" + p + "/ on \"" + DotLineTerminatorTest.escape(in) + "\": " + diff);
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  /** Named-only matchers drop unnamed groups, so only the overall match span is compared. */
  @Test
  void namedOnlyAgreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    for (String p : NAMED_ONLY) {
      LinearTokenSequenceMatcher m =
          RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence(p, 0).matcher();
      assertNotNull(m, p + " must be admitted to keep covering the LTS path");
      Pattern j = Pattern.compile(p);
      for (String in : inputs()) {
        Matcher jm = j.matcher(in);
        String jdk = jm.matches() ? "[0," + in.length() + ")" : "-";
        String reggie = m.matches(in) ? "[0," + in.length() + ")" : "-";
        jm = j.matcher(in);
        jdk += jm.find() ? "[" + jm.start() + "," + jm.end() + ")" : "-";
        MatchResult r = m.findMatch(in);
        reggie += r == null ? "-" : "[" + r.start() + "," + r.end() + ")";
        if (!jdk.equals(reggie)) {
          mismatches.add(
              "/"
                  + p
                  + "/ on \""
                  + DotLineTerminatorTest.escape(in)
                  + "\": jdk="
                  + jdk
                  + " reggie="
                  + reggie);
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }
}
