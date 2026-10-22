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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Review regressions in compilation-cache hygiene: the classfile size scanner's constant-pool
 * parsing, the JIT size gate on structural-cache hits, negative prefilter-fact caching, and
 * clearCache coverage of the caches added on this branch.
 */
class CompileCacheHygieneTest {

  /**
   * The size scanner must consume the full 8-byte payload of CONSTANT_Long/Double. The old 4-byte
   * read left half the payload to be misread as the next constant tag, so any generated class with
   * a long constant (e.g. 64-bit SWAR masks) failed the scan with -1 and skipped the JIT gate
   * entirely.
   */
  @Test
  void sizeScannerParsesLongConstants() {
    // minimal classfile: magic, version, pool [1]=Long (occupies slots 1-2), [3]=Class,
    // then an empty class body (0 interfaces/fields/methods/attributes)
    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64);
    buf.putInt(0xCAFEBABE);
    buf.putShort((short) 0); // minor
    buf.putShort((short) 52); // major
    buf.putShort((short) 4); // constant_pool_count: slots 1,2(long) + 3(class)
    buf.put((byte) 5); // CONSTANT_Long
    buf.putLong(0x1122334455667788L); // 8-byte payload
    buf.put((byte) 7); // CONSTANT_Class
    buf.putShort((short) 1); // name_index
    buf.putShort((short) 0); // access_flags
    buf.putShort((short) 3); // this_class
    buf.putShort((short) 0); // super_class
    buf.putShort((short) 0); // interfaces_count
    buf.putShort((short) 0); // fields_count
    buf.putShort((short) 0); // methods_count
    buf.putShort((short) 0); // attributes_count
    byte[] bytes = new byte[buf.position()];
    buf.flip();
    buf.get(bytes);
    assertEquals(0, RuntimeCompiler.largestMethodBytecodes(bytes), "no methods -> 0, not -1");
  }

  /**
   * The JIT size gate must also apply on verified structural-cache hits: a strict compile caches
   * the oversized class, and a later fallback-enabled compile of the same structure (different
   * cache key) used to get the oversized class back without the gate. The hit takes the same route
   * as an uncached fallback-enabled compile: the Laurikari/JDK split for this Laurikari-eligible
   * pattern.
   */
  @Test
  void jitSizeGateAppliesOnStructuralCacheHit() {
    String monster = "^(?:[^a-z]*[a-z]+){40}";
    RuntimeCompiler.clearCache();
    // strict compile: oversized but kept (native-or-throw contract), class lands in the
    // structural cache
    assertFalse(Reggie.compile(monster).isJdkFallback());
    // same structure, fresh cache key, fallback enabled: must not reuse the oversized class
    RuntimeCompiler.ROUTING_NOTE.remove();
    RuntimeCompiler.cached(
        "jit-gate-hit-test", monster, ReggieOptions.builder().allowJdkFallback().build());
    String hitRoute = RuntimeCompiler.ROUTING_NOTE.get();
    assertEquals(
        "LAURIKARI find / JDK matches (over JIT limit)",
        hitRoute,
        "structural-cache hit must apply the JIT size gate");
    assertEquals(RuntimeCompiler.describeRouting(monster).routing, hitRoute);
  }

  /**
   * A pattern with no usable prefilter fact must still be cached: computeIfAbsent drops null
   * values, so fact-less NFA-backed patterns used to re-run the parse+analysis on every compile().
   */
  @Test
  void missingPrefilterFactIsCached() throws Exception {
    RuntimeCompiler.clearCache();
    String noFact = "(?:a|b)*c"; // alternation star: no required literal, no required char
    Reggie.compile(noFact);
    Reggie.compile(noFact);
    Field f = RuntimeCompiler.class.getDeclaredField("LITERAL_CACHE");
    f.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, ?> cache = (Map<String, ?>) f.get(null);
    assertTrue(cache.containsKey(noFact), "negative fact must be cached (sentinel entry)");
  }

  /** clearCache() must clear every cache added on this branch, not just the original five. */
  @Test
  void clearCacheClearsBranchCaches() throws Exception {
    RuntimeCompiler.clearCache();
    Reggie.compile("(?:a|b)*c"); // literal cache (sentinel)
    Reggie.compile("a{0,6000}"); // counted-loop cache
    RuntimeCompiler.compileBackrefBacktrack("(.*) \\((.*)\\)", ""); // stub backtracker
    Reggie.compile("(a|b)*(c)"); // hybrid
    Reggie.compile(
        "^(?:[^a-z]*[a-z]+){40}",
        ReggieOptions.builder().allowJdkFallback().build()); // over-JIT-limit split
    String[] caches = {
      "LITERAL_CACHE",
      "BACKTRACK_NFA_CACHE",
      "STUB_BACKTRACK_NFA_CACHE",
      "HYBRID_CACHE",
      "OVER_JIT_SPLIT_CACHE"
    };
    for (String name : caches) {
      Field f = RuntimeCompiler.class.getDeclaredField(name);
      f.setAccessible(true);
      assertFalse(((Map<?, ?>) f.get(null)).isEmpty(), name + " must be populated before clear");
    }
    RuntimeCompiler.clearCache();
    for (String name : caches) {
      Field f = RuntimeCompiler.class.getDeclaredField(name);
      f.setAccessible(true);
      assertTrue(((Map<?, ?>) f.get(null)).isEmpty(), name + " must be cleared");
    }
  }

  @Test
  void stubAndRuntimeBacktrackCachesUseSeparateNamespaces() throws Exception {
    String pattern = "(.*) \\((.*)\\)";
    RuntimeCompiler.clearCache();
    RuntimeCompiler.compileBackrefBacktrack(pattern, "");
    assertEquals(1, cache("STUB_BACKTRACK_NFA_CACHE").size());
    assertTrue(cache("BACKTRACK_NFA_CACHE").isEmpty());

    RuntimeCompiler.clearCache();
    RuntimeCompiler.compile(pattern);
    assertFalse(cache("BACKTRACK_NFA_CACHE").isEmpty());
    assertTrue(cache("STUB_BACKTRACK_NFA_CACHE").isEmpty());
  }

  private static Map<?, ?> cache(String name) throws Exception {
    Field field = RuntimeCompiler.class.getDeclaredField(name);
    field.setAccessible(true);
    return (Map<?, ?>) field.get(null);
  }
}
