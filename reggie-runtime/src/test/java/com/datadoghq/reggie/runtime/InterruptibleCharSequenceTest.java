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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InterruptibleCharSequenceTest {

  @Test
  void checksBeforeInputValidationAndPropagatesTheOriginalException() {
    ReggieMatchState state = stateFor("(?<value>\\S+)");
    Cancellation cancellation = new Cancellation();
    InterruptibleCharSequence input =
        new Probe("value", 1) {
          @Override
          public int length() {
            if (checks == 0) throw new AssertionError("length accessed before interruption check");
            return super.length();
          }

          @Override
          void check() {
            throw cancellation;
          }
        };

    assertSame(cancellation, assertThrows(Cancellation.class, () -> state.matches(input, -1, 5)));
    assertThrows(IllegalStateException.class, state::start);
  }

  @Test
  void checksLongNativeScansAtMostEvery256CharactersAndClearsPriorState() {
    ReggieMatchState state = stateFor("(?<value>\\S+)");
    assertTrue(state.matches("prior", 0, 5));
    Probe input = new Probe("a".repeat(1024), 2);

    assertThrows(Cancellation.class, () -> state.matches(input, 0, input.length()));
    assertTrue(input.maximumGap <= 256, () -> "maximum checkpoint gap: " + input.maximumGap);
    assertThrows(IllegalStateException.class, () -> state.start("value"));
  }

  @Test
  void onlyInterruptibleInputsUseTheCheckpointPath() {
    ReggieMatchState plainState = stateFor("(?<value>\\S+)");
    assertTrue(plainState.matches("value", 0, 5));
    assertFalse(plainState.usedInterruptibleExecution());

    ReggieMatchState interruptibleState = stateFor("(?<value>\\S+)");
    Probe input = new Probe("value", Integer.MAX_VALUE);
    assertTrue(interruptibleState.matches(input, 0, input.length()));
    assertTrue(interruptibleState.usedInterruptibleExecution());
    assertEquals(0, interruptibleState.start("value"));
    assertEquals(5, interruptibleState.end("value"));
  }

  @Test
  void interruptionChecksRunOnTheMatchingCallerThread() {
    Thread caller = Thread.currentThread();
    ReggieMatchState state = stateFor("(?<value>\\S+)");
    Probe input =
        new Probe("value", 1) {
          @Override
          void check() {
            assertEquals(caller, Thread.currentThread());
          }
        };
    assertTrue(state.matches(input, 0, input.length()));
  }

  @Test
  void checkpointsOptionalHttpAndIpOrHostValidationPaths() {
    String target = "a".repeat(600);
    ReggieMatchState optionalHttp =
        stateFor("\"(?<method>\\b\\w+\\b) (?<target>\\S+)(?: HTTP/(?<version>\\d+\\.\\d+)|)\"");
    Probe optionalInput = new Probe("\"GET " + target + " HTTP/1.1\"", 4);
    assertThrows(
        Cancellation.class, () -> optionalHttp.matches(optionalInput, 0, optionalInput.length()));
    assertTrue(optionalInput.maximumGap <= 256);

    Probe decimalInput = new Probe("\"GET " + target + " HTTP/" + "1".repeat(6000) + ".1\"", 60);
    assertThrows(
        Cancellation.class, () -> optionalHttp.matches(decimalInput, 0, decimalInput.length()));
    assertTrue(decimalInput.maximumGap <= 256);

    // Full numeric capture admission intentionally declines the IP-or-host alternation because
    // it cannot prove one source-group boundary for both branches. This interruption regression
    // needs only a long native non-space scan.
    ReggieMatchState ipOrHost = stateFor("(?<client>\\S+)");
    Probe hostInput = new Probe(target, 3);
    assertThrows(Cancellation.class, () -> ipOrHost.matches(hostInput, 0, hostInput.length()));
    assertTrue(hostInput.maximumGap <= 256);
  }

  @Test
  void checkpointsLiteralPrefixAndTailConsumptionScans() {
    assertCancels("literal".repeat(100) + "(?<value>\\S+)", "literal".repeat(100) + "x");
  }

  @Test
  void nonCancellingInterruptibleInputMatchesPlainInputWithoutCacheMutation() {
    int patterns = RuntimeCompiler.cacheSize();
    int structures = RuntimeCompiler.structuralCacheSize();
    ReggieMatchState plain = stateFor("host=(?<host>\\S+)");
    ReggieMatchState interruptible = stateFor("host=(?<host>\\S+)");
    String source = "xxhost=api";
    assertTrue(plain.matches(source, 2, source.length()));
    Probe input = new Probe(source, Integer.MAX_VALUE);
    assertTrue(interruptible.matches(input, 2, input.length()));
    assertEquals(plain.start(), interruptible.start());
    assertEquals(plain.end(), interruptible.end());
    assertEquals(plain.start("host"), interruptible.start("host"));
    assertEquals(plain.end("host"), interruptible.end("host"));
    assertEquals(patterns, RuntimeCompiler.cacheSize());
    assertEquals(structures, RuntimeCompiler.structuralCacheSize());
  }

  private static void assertCancels(String pattern, String source) {
    assertCancels(pattern, source, ReggieCompileFlag.NONE);
  }

  private static void assertCancels(String pattern, String source, ReggieCompileFlag flag) {
    ReggieMatchState state = stateFor(pattern, flag);
    Probe input = new Probe(source, 2);
    assertThrows(Cancellation.class, () -> state.matches(input, 0, input.length()));
    assertTrue(input.maximumGap <= 256);
  }

  private static ReggieMatchState stateFor(String source) {
    return stateFor(source, ReggieCompileFlag.NONE);
  }

  private static ReggieMatchState stateFor(String source, ReggieCompileFlag flag) {
    ReggieCompilationResult result =
        ReggieCompiledPattern.tryCompile(new ReggieCompileRequest(source, flag));
    assertTrue(result.isAdmitted());
    return result.pattern().newState();
  }

  private static class Probe implements InterruptibleCharSequence {
    private final String value;
    private final int throwOnCheck;
    private int charactersSinceCheck;
    protected int checks;
    int maximumGap;

    Probe(String value, int throwOnCheck) {
      this.value = value;
      this.throwOnCheck = throwOnCheck;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      charactersSinceCheck++;
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new AssertionError("matching must not materialize a subsequence");
    }

    @Override
    public String toString() {
      throw new AssertionError("matching must not materialize input");
    }

    @Override
    public final void checkInterrupted() {
      checks++;
      maximumGap = Math.max(maximumGap, charactersSinceCheck);
      charactersSinceCheck = 0;
      if (checks == throwOnCheck) check();
    }

    void check() {
      throw new Cancellation();
    }
  }

  private static final class Cancellation extends RuntimeException {}
}
