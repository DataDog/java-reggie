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

class StateSetTest {

  // ── BitSet backend (capacity ≤ 64) ─────────────────────────────────────────

  @Test
  void bitsetBackendAddContains() {
    StateSet s = new StateSet(10);
    assertFalse(s.contains(3));
    s.add(3);
    assertTrue(s.contains(3));
  }

  @Test
  void bitsetBackendSize() {
    StateSet s = new StateSet(10);
    assertEquals(0, s.size());
    s.add(0);
    s.add(5);
    s.add(9);
    assertEquals(3, s.size());
  }

  @Test
  void bitsetBackendIsEmpty() {
    StateSet s = new StateSet(10);
    assertTrue(s.isEmpty());
    s.add(1);
    assertFalse(s.isEmpty());
  }

  @Test
  void bitsetBackendClear() {
    StateSet s = new StateSet(10);
    s.add(1);
    s.add(7);
    s.clear();
    assertTrue(s.isEmpty());
    assertEquals(0, s.size());
    assertFalse(s.contains(1));
  }

  @Test
  void bitsetBackendAddDuplicateIsSafe() {
    StateSet s = new StateSet(10);
    s.add(4);
    s.add(4);
    assertEquals(1, s.size());
  }

  @Test
  void bitsetBackendGet() {
    StateSet s = new StateSet(10);
    s.add(2);
    s.add(7);
    // get(0) returns first set bit in order, get(1) returns second
    int first = s.get(0);
    int second = s.get(1);
    assertTrue((first == 2 && second == 7) || (first == 7 && second == 2));
  }

  @Test
  void bitsetBackendNextSetBitFound() {
    StateSet s = new StateSet(20);
    s.add(5);
    s.add(15);
    assertEquals(5, s.nextSetBit(0));
    assertEquals(5, s.nextSetBit(5));
    assertEquals(15, s.nextSetBit(6));
  }

  @Test
  void bitsetBackendNextSetBitNotFound() {
    StateSet s = new StateSet(20);
    s.add(5);
    assertEquals(-1, s.nextSetBit(6));
  }

  @Test
  void bitsetBackendIteratorAllElements() {
    StateSet s = new StateSet(10);
    s.add(1);
    s.add(4);
    s.add(9);

    StateSet.Iterator iter = s.iterator();
    int count = 0;
    while (iter.hasNext()) {
      int bit = iter.nextSetBit();
      assertTrue(bit >= 0);
      count++;
    }
    assertEquals(3, count);
  }

  @Test
  void bitsetBackendIteratorEmpty() {
    StateSet s = new StateSet(10);
    StateSet.Iterator iter = s.iterator();
    assertFalse(iter.hasNext());
    assertEquals(-1, iter.nextSetBit());
  }

  @Test
  void bitsetBackendIteratorResetRestarts() {
    StateSet s = new StateSet(10);
    s.add(2);
    s.add(6);

    StateSet.Iterator iter = s.iterator();
    iter.nextSetBit(); // consume first
    iter.reset(); // restart
    assertEquals(2, iter.nextSetBit()); // back at first element
  }

  @Test
  void bitsetBackendIteratorImplicitResetOnFirstCall() {
    StateSet s = new StateSet(10);
    s.add(3);
    StateSet.Iterator iter = s.iterator();
    // nextSetBit without explicit reset should auto-initialize
    assertEquals(3, iter.nextSetBit());
  }

  @Test
  void bitsetBackendHasNextReflectsState() {
    StateSet s = new StateSet(10);
    s.add(1);
    StateSet.Iterator iter = s.iterator();
    assertTrue(iter.hasNext());
    iter.nextSetBit();
    assertFalse(iter.hasNext());
  }

  // ── SparseSet backend (capacity > 64) ──────────────────────────────────────

  @Test
  void sparseBackendAddContains() {
    StateSet s = new StateSet(100);
    assertFalse(s.contains(70));
    s.add(70);
    assertTrue(s.contains(70));
  }

  @Test
  void sparseBackendSize() {
    StateSet s = new StateSet(100);
    assertEquals(0, s.size());
    s.add(10);
    s.add(50);
    s.add(99);
    assertEquals(3, s.size());
  }

  @Test
  void sparseBackendIsEmpty() {
    StateSet s = new StateSet(100);
    assertTrue(s.isEmpty());
    s.add(80);
    assertFalse(s.isEmpty());
  }

  @Test
  void sparseBackendClear() {
    StateSet s = new StateSet(100);
    s.add(65);
    s.add(90);
    s.clear();
    assertTrue(s.isEmpty());
    assertFalse(s.contains(65));
  }

  @Test
  void sparseBackendGet() {
    StateSet s = new StateSet(100);
    s.add(70);
    assertEquals(70, s.get(0));
  }

  @Test
  void sparseBackendNextSetBitFound() {
    StateSet s = new StateSet(200);
    s.add(80);
    s.add(150);
    assertEquals(80, s.nextSetBit(0));
    assertEquals(150, s.nextSetBit(81));
  }

  @Test
  void sparseBackendNextSetBitNotFound() {
    StateSet s = new StateSet(200);
    s.add(80);
    assertEquals(-1, s.nextSetBit(81));
  }

  @Test
  void sparseBackendIteratorAllElements() {
    StateSet s = new StateSet(200);
    s.add(65);
    s.add(100);
    s.add(199);

    StateSet.Iterator iter = s.iterator();
    int count = 0;
    while (iter.hasNext()) {
      iter.nextSetBit();
      count++;
    }
    assertEquals(3, count);
  }

  @Test
  void sparseBackendIteratorEmpty() {
    StateSet s = new StateSet(200);
    StateSet.Iterator iter = s.iterator();
    assertFalse(iter.hasNext());
    assertEquals(-1, iter.nextSetBit());
  }

  @Test
  void sparseBackendIteratorResetRestarts() {
    StateSet s = new StateSet(200);
    s.add(70);
    s.add(130);

    StateSet.Iterator iter = s.iterator();
    iter.nextSetBit(); // consume
    iter.reset();
    // After reset sparseIndex=0; first nextSetBit returns element at index 0
    assertTrue(iter.nextSetBit() >= 0);
  }

  @Test
  void sparseBackendIteratorExhaustedThenNegativeOne() {
    StateSet s = new StateSet(100);
    s.add(65);
    StateSet.Iterator iter = s.iterator();
    iter.nextSetBit(); // consume the only element
    assertEquals(-1, iter.nextSetBit());
  }

  // ── Boundary between backends ───────────────────────────────────────────────

  @Test
  void exactlyAtThresholdUsesBitset() {
    StateSet s = new StateSet(64); // threshold is 64
    s.add(63);
    assertTrue(s.contains(63));
    assertEquals(1, s.size());
  }

  @Test
  void oneAboveThresholdUsesSparse() {
    StateSet s = new StateSet(65); // > 64 → SparseSet
    s.add(64);
    assertTrue(s.contains(64));
    assertEquals(1, s.size());
  }
}
