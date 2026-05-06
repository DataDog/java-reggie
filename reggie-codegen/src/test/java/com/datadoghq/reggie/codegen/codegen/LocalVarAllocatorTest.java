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
package com.datadoghq.reggie.codegen.codegen;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocalVarAllocatorTest {

  // ── allocate() ─────────────────────────────────────────────────────────────

  @Test
  void allocateIsAlwaysSequential() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    assertEquals(1, a.allocate());
    assertEquals(2, a.allocate());
    assertEquals(3, a.allocate());
  }

  @Test
  void allocateStartsAtStartSlot() {
    LocalVarAllocator a = new LocalVarAllocator(4);
    assertEquals(4, a.allocate());
  }

  // ── allocateWide() / allocateLong() ────────────────────────────────────────

  @Test
  void allocateLongIsSequential() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    assertEquals(1, a.allocateLong()); // occupies 1 and 2
    assertEquals(3, a.allocateLong()); // occupies 3 and 4
  }

  @Test
  void allocateWideReusesFreeWideSlotsAboveBoundary() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    int w = a.allocateWide(); // slots 1-2
    assertEquals(1, w);
    a.release(w); // adds 1 to freeWideSlots
    int w2 = a.allocateWide(); // should reuse 1-2
    assertEquals(1, w2);
  }

  @Test
  void allocateWideFindsConsecutiveSingleSlots() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    int s1 = a.allocate(); // slot 1
    int s2 = a.allocate(); // slot 2
    a.allocate(); // slot 3 — keep allocated
    a.release(s1);
    a.release(s2);
    // Slots 1 and 2 are free; wide allocation should pick them up
    int w = a.allocateWide();
    assertEquals(1, w);
  }

  @Test
  void allocateWideAllocatesNewSlotsWhenNoFreeConsecutive() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    a.allocate(); // slot 1 — keep
    int w = a.allocateWide(); // no consecutive free, allocates 2-3
    assertEquals(2, w);
  }

  @Test
  void allocateWideCleansUpSingleFreeLists() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    int s1 = a.allocate(); // 1
    int s2 = a.allocate(); // 2
    a.release(s1);
    a.release(s2);
    int w = a.allocateWide(); // consumes slots 1-2
    assertEquals(1, w);
    // After consuming, next single allocation must be new
    int next = a.allocate();
    assertEquals(3, next);
  }

  // ── allocate(int count) ────────────────────────────────────────────────────

  @Test
  void allocateCountOneDelegate() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    assertEquals(1, a.allocate(1));
    assertEquals(2, a.allocate(1));
  }

  @Test
  void allocateCountTwoDelegateToWide() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    assertEquals(1, a.allocate(2)); // occupies 1-2
    assertEquals(3, a.allocate(1));
  }

  @Test
  void allocateCountThreeSequential() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    int first = a.allocate(3); // needs 3 consecutive; fresh allocator → 1,2,3
    assertEquals(1, first);
    assertEquals(4, a.allocate()); // next single starts at 4
  }

  @Test
  void allocateCountThreeFindsConsecutiveFree() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    int s1 = a.allocate(); // 1
    int s2 = a.allocate(); // 2
    int s3 = a.allocate(); // 3
    int s4 = a.allocate(); // 4 — keep
    a.release(s1);
    a.release(s2);
    a.release(s3);
    int first = a.allocate(3);
    assertEquals(1, first);
    // s4 still allocated; next sequential starts at 5
    assertEquals(5, a.allocate());
  }

  // ── release() ─────────────────────────────────────────────────────────────

  @Test
  void releaseUnallocatedSlotIsNoOp() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    a.release(5); // never allocated — should not throw
    assertEquals(1, a.allocate()); // allocator state unchanged
  }

  @Test
  void releaseSecondSlotOfWideIsNoOp() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    int w = a.allocateWide(); // 1-2
    a.release(w + 1); // releasing slot 2 (second half) should be no-op
    // Wide still held; next allocation is sequential
    assertEquals(3, a.allocate());
    // Proper release via first slot works
    a.release(w);
    int reused = a.allocateWide();
    assertEquals(1, reused);
  }

  @Test
  void releaseWideSlotReturnsToFreeList() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    int w = a.allocateWide(); // 1-2
    a.release(w);
    assertEquals(1, a.allocateWide()); // reused
  }

  @Test
  void releaseSingleSlotBelowBoundaryNotAddedToFreeList() {
    LocalVarAllocator a = new LocalVarAllocator(3); // boundary=3, params 0-2
    // Slots 0-2 are "parameters" — releasing them should not add to free list
    a.release(1); // below boundary
    // allocate() is always sequential from boundary
    assertEquals(3, a.allocate());
  }

  @Test
  void releaseCountReleasesEachSlot() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    a.allocate(); // 1
    a.allocate(); // 2
    a.allocate(); // 3 — keep
    a.release(1, 2); // release 1 and 2
    // allocate() is sequential; free list doesn't feed allocate()
    // but allocateWide can find consecutive slots
    int w = a.allocateWide();
    assertEquals(1, w);
  }

  // ── peek() ────────────────────────────────────────────────────────────────

  @Test
  void peekReturnsNextSlotWithoutAllocating() {
    LocalVarAllocator a = new LocalVarAllocator(2);
    assertEquals(2, a.peek());
    assertEquals(2, a.peek()); // idempotent
    a.allocate();
    assertEquals(3, a.peek());
  }

  // ── reserve(int slotNumber) ───────────────────────────────────────────────

  @Test
  void reserveExtendsNextSlot() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    a.reserve(5);
    assertEquals(6, a.peek()); // nextSlot moved to 6
  }

  @Test
  void reserveBelowNextSlotIsNoOpForPeek() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    a.allocate(); // nextSlot=2
    a.allocate(); // nextSlot=3
    a.reserve(1); // below nextSlot — peek unchanged
    assertEquals(3, a.peek());
  }

  @Test
  void reserveRemovesSlotFromWideFreeLists() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    int w = a.allocateWide(); // slots 1-2
    a.release(w); // slot 1 in freeWideSlots
    a.reserve(1); // slot 1 now explicitly reserved
    // freeWideSlots{1} was cleared by reserve; consecutive scan finds free slots 2-3
    int w2 = a.allocateWide();
    assertEquals(2, w2);
  }

  // ── snapshot() / restore() ────────────────────────────────────────────────

  @Test
  void snapshotRestoreResetsNextSlot() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    a.allocate(); // 1
    LocalVarAllocator.Snapshot snap = a.snapshot();
    a.allocate(); // 2
    a.allocate(); // 3
    a.restore(snap);
    assertEquals(2, a.peek()); // nextSlot back to 2
  }

  @Test
  void snapshotRestoreRestoresWideFreeList() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    int w = a.allocateWide(); // 1-2
    a.release(w); // 1 in freeWideSlots
    LocalVarAllocator.Snapshot snap = a.snapshot();
    // Consume the free wide slot
    a.allocateWide(); // reuses 1
    assertEquals(3, a.peek());
    // Restore — free list re-appears
    a.restore(snap);
    assertEquals(1, a.allocateWide()); // free slot back
  }

  @Test
  void snapshotIsIndependent() {
    LocalVarAllocator a = new LocalVarAllocator(1);
    LocalVarAllocator.Snapshot snap1 = a.snapshot();
    a.allocate(); // 1
    LocalVarAllocator.Snapshot snap2 = a.snapshot();
    a.allocate(); // 2

    a.restore(snap2);
    assertEquals(2, a.peek());

    a.restore(snap1);
    assertEquals(1, a.peek());
  }
}
