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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Tests for SparseSet data structure. */
public class SparseSetTest {

  @Test
  public void testEmptySet() {
    SparseSet set = new SparseSet(10);
    assertTrue(set.isEmpty());
    assertEquals(0, set.size());
    assertFalse(set.contains(0));
    assertFalse(set.contains(5));
  }

  @Test
  public void testAddAndContains() {
    SparseSet set = new SparseSet(10);

    set.add(5);
    assertTrue(set.contains(5));
    assertFalse(set.contains(3));
    assertEquals(1, set.size());
    assertFalse(set.isEmpty());
  }

  @Test
  public void testAddMultiple() {
    SparseSet set = new SparseSet(10);

    set.add(0);
    set.add(5);
    set.add(9);

    assertTrue(set.contains(0));
    assertTrue(set.contains(5));
    assertTrue(set.contains(9));
    assertFalse(set.contains(1));
    assertFalse(set.contains(7));
    assertEquals(3, set.size());
  }

  @Test
  public void testAddDuplicate() {
    SparseSet set = new SparseSet(10);

    set.add(5);
    set.add(5);
    set.add(5);

    assertEquals(1, set.size());
    assertTrue(set.contains(5));
  }

  @Test
  public void testClear() {
    SparseSet set = new SparseSet(10);

    set.add(0);
    set.add(5);
    set.add(9);
    assertEquals(3, set.size());

    set.clear();
    assertEquals(0, set.size());
    assertTrue(set.isEmpty());
    assertFalse(set.contains(0));
    assertFalse(set.contains(5));
    assertFalse(set.contains(9));
  }

  @Test
  public void testClearAndReuse() {
    SparseSet set = new SparseSet(10);

    // First batch
    set.add(1);
    set.add(2);
    set.add(3);
    assertEquals(3, set.size());

    // Clear
    set.clear();
    assertEquals(0, set.size());

    // Reuse with different elements
    set.add(7);
    set.add(8);
    assertEquals(2, set.size());
    assertTrue(set.contains(7));
    assertTrue(set.contains(8));
    assertFalse(set.contains(1));
    assertFalse(set.contains(2));
  }

  @Test
  public void testIteration() {
    SparseSet set = new SparseSet(10);

    set.add(5);
    set.add(2);
    set.add(8);

    assertEquals(3, set.size());
    assertEquals(5, set.get(0));
    assertEquals(2, set.get(1));
    assertEquals(8, set.get(2));
  }

  @Test
  public void testBoundaryValues() {
    SparseSet set = new SparseSet(10);

    // Test min value
    set.add(0);
    assertTrue(set.contains(0));

    // Test max value
    set.add(9);
    assertTrue(set.contains(9));

    assertEquals(2, set.size());
  }

  @Test
  public void testClearPerformance() {
    int capacity = 1000;
    SparseSet set = new SparseSet(capacity);

    // Add many elements
    for (int i = 0; i < capacity; i += 10) {
      set.add(i);
    }
    assertEquals(100, set.size());

    // Clear should be O(1) regardless of how many elements were added
    long start = System.nanoTime();
    set.clear();
    long duration = System.nanoTime() - start;

    assertEquals(0, set.size());
    assertTrue(set.isEmpty());

    // Clear should be very fast (< 1ms even on slow machines)
    assertTrue(duration < 1_000_000, "Clear took " + duration + " ns, expected < 1ms");
  }

  @Test
  public void testContainsAfterClear() {
    SparseSet set = new SparseSet(10);

    set.add(5);
    assertTrue(set.contains(5));

    set.clear();
    assertFalse(set.contains(5));

    // Add same element again
    set.add(5);
    assertTrue(set.contains(5));
    assertEquals(1, set.size());
  }
}
