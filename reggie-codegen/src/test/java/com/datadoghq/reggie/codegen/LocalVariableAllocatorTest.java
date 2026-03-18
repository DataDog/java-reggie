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
package com.datadoghq.reggie.codegen;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.codegen.LocalVariableAllocator;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for LocalVariableAllocator's slot allocation and reuse logic with scope-based
 * management. Validates aggressive slot reuse for both single and wide slots, type safety via
 * snapshot/restore, parameter protection, and edge cases.
 */
class LocalVariableAllocatorTest {

  @Test
  void testSingleSlotReuseWithRestore() {
    // Given an allocator starting at slot 3 (simulating 3 method parameters)
    LocalVariableAllocator allocator = new LocalVariableAllocator(3);

    // When allocating a single slot
    int slot1 = allocator.allocateInt();
    assertEquals(3, slot1, "First allocation should use slot 3");

    // Take snapshot and release slot
    LocalVariableAllocator.Snapshot snapshot = allocator.snapshot();
    allocator.release(slot1);

    // Then allocating again should reuse the same slot
    int slot2 = allocator.allocateInt();
    assertEquals(3, slot2, "Second allocation should reuse slot 3");

    // Restore should make the slot available again
    allocator.restore(snapshot);
    int slot3 = allocator.allocateInt();
    assertEquals(4, slot3, "After restore, should allocate next sequential slot");
  }

  @Test
  void testWideSlotReuse() {
    // Given an allocator starting at slot 2
    LocalVariableAllocator allocator = new LocalVariableAllocator(2);

    // When allocating a wide slot (long/double)
    int wideSlot1 = allocator.allocateLong();
    assertEquals(2, wideSlot1, "First wide allocation should use slot 2");

    // And releasing it
    allocator.release(wideSlot1);

    // Then allocating again should reuse the same wide slot
    int wideSlot2 = allocator.allocateLong();
    assertEquals(2, wideSlot2, "Second wide allocation should reuse slot 2");
  }

  @Test
  void testTypeSafetyWideToSingle() {
    // Given an allocator with a freed wide slot
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    int wideSlot = allocator.allocateLong(); // Allocates slots 1-2
    allocator.release(wideSlot);

    // When allocating a single slot
    int singleSlot = allocator.allocateInt();

    // With snapshot/restore-based reuse, single allocation doesn't reuse wide slots from free list
    // The wide slot stays in freeWideSlots, single allocation goes to frontier
    assertNotEquals(1, singleSlot, "Single allocation should not reuse wide slot");
    assertEquals(3, singleSlot, "Should allocate new sequential slot instead");
  }

  @Test
  void testTypeSafetySingleToWide() {
    // Given an allocator with two adjacent freed single slots
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    int slot1 = allocator.allocateInt(); // slot 1
    int slot2 = allocator.allocateInt(); // slot 2
    allocator.release(slot1);
    allocator.release(slot2);

    // When allocating a wide slot
    int wideSlot = allocator.allocateLong();

    // Then it SHOULD find and use the consecutive free slots
    assertEquals(1, wideSlot, "Wide allocation should find consecutive freed single slots");
  }

  @Test
  void testParameterProtection() {
    // Given an allocator starting at slot 3 (protecting slots 0-2)
    LocalVariableAllocator allocator = new LocalVariableAllocator(3);

    // When releasing a parameter slot (artificially)
    allocator.release(1);

    // Then allocating should NOT reuse the parameter slot
    int newSlot = allocator.allocateInt();
    assertEquals(3, newSlot, "Should not reuse parameter slots");
  }

  @Test
  void testSnapshotRestore() {
    // Given an allocator with some freed slots
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    int slot1 = allocator.allocateInt(); // slot 1
    int slot2 = allocator.allocateInt(); // slot 2
    allocator.release(slot1);

    // When taking a snapshot
    LocalVariableAllocator.Snapshot snapshot = allocator.snapshot();

    // And allocating more slots
    int slot3 = allocator.allocateInt(); // Should reuse slot 1
    assertEquals(1, slot3, "Should reuse freed slot");

    // Then restoring snapshot should restore free lists
    allocator.restore(snapshot);
    int slot4 = allocator.allocateInt();
    assertEquals(1, slot4, "After restore, should reuse same freed slot");
  }

  @Test
  void testConsecutiveSlotsFoundDuringWideScan() {
    // Given an allocator with two consecutive freed single slots
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    int slot1 = allocator.allocateInt(); // slot 1
    int slot2 = allocator.allocateInt(); // slot 2
    int slot3 = allocator.allocateInt(); // slot 3
    allocator.release(slot1);
    allocator.release(slot2);

    // When allocating a wide slot
    int wideSlot = allocator.allocateLong();

    // Then it should find the consecutive freed single slots
    assertEquals(1, wideSlot, "Should find consecutive freed single slots");

    // And those slots should no longer be available as singles
    // Note: slot3 is still allocated, so next allocation is slot 4
    int newSingle = allocator.allocateInt();
    assertEquals(
        4, newSingle, "Single slots 1-2 are now used by wide allocation, slot 3 still allocated");
  }

  @Test
  void testReleaseSecondSlotOfWide() {
    // Given an allocator with a wide slot allocated
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    int wideSlot = allocator.allocateLong(); // Allocates slots 1-2

    // When trying to release the second slot (slot 2)
    allocator.release(wideSlot + 1);

    // Then it should be a no-op (only release via first slot)
    // Verify by trying to allocate - should get new slot, not reuse
    int newSlot = allocator.allocateInt();
    assertEquals(3, newSlot, "Second slot release should be no-op");

    // Properly release the wide slot
    allocator.release(wideSlot);

    // Now reallocation should work
    int reusedWide = allocator.allocateLong();
    assertEquals(1, reusedWide, "Should reuse wide slot after proper release");
  }

  @Test
  void testReserveRemovesFromFreeList() {
    // Given an allocator with freed slots
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    int slot1 = allocator.allocateInt(); // slot 1
    int slot2 = allocator.allocateInt(); // slot 2
    allocator.release(slot1);
    allocator.release(slot2);

    // When reserving one of the freed slots externally
    allocator.reserveSlot(1);

    // Then subsequent allocation should NOT reuse the reserved slot
    int newSlot = allocator.allocateInt();
    assertEquals(2, newSlot, "Should skip reserved slot and use next freed slot");
  }

  @Test
  void testAllocateMultipleSlots() {
    // Test the reserve(int count) method that returns start slot
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);

    // When allocating 3 consecutive slots via reserve
    int firstSlot = allocator.reserve(3);

    // Then it should allocate slots 1-3
    assertEquals(1, firstSlot, "Should allocate starting from slot 1");

    // And next allocation should start at slot 4
    int nextSlot = allocator.allocateInt();
    assertEquals(4, nextSlot, "Next allocation should be slot 4");
  }

  @Test
  void testReleaseMultipleSlots() {
    // Given an allocator with multiple allocated slots
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    int slot1 = allocator.allocateInt(); // slot 1
    int slot2 = allocator.allocateInt(); // slot 2
    int slot3 = allocator.allocateInt(); // slot 3

    // When releasing multiple consecutive slots
    allocator.release(slot1, 2); // Release slots 1-2

    // Then both should be available for reuse
    int reused1 = allocator.allocateInt();
    int reused2 = allocator.allocateInt();
    assertEquals(1, reused1, "Should reuse slot 1");
    assertEquals(2, reused2, "Should reuse slot 2");
  }

  @Test
  void testMixedSingleAndWideAllocations() {
    // Given an allocator
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);

    // When mixing single and wide allocations
    int single1 = allocator.allocateInt(); // slot 1
    int wide1 = allocator.allocateLong(); // slots 2-3
    int single2 = allocator.allocateInt(); // slot 4
    int wide2 = allocator.allocateLong(); // slots 5-6

    assertEquals(1, single1);
    assertEquals(2, wide1);
    assertEquals(4, single2);
    assertEquals(5, wide2);

    // Release some slots
    allocator.release(single1);
    allocator.release(wide1);

    // Then allocating should reuse appropriately
    int reusedSingle = allocator.allocateInt();
    int reusedWide = allocator.allocateLong();

    assertEquals(1, reusedSingle, "Should reuse freed single slot");
    assertEquals(2, reusedWide, "Should reuse freed wide slot");
  }

  @Test
  void testGetNextSlot() {
    // Given an allocator
    LocalVariableAllocator allocator = new LocalVariableAllocator(2);

    // When peeking at next slot
    int peeked1 = allocator.getNextSlot();
    assertEquals(2, peeked1, "getNextSlot should return next slot without allocating");

    // Then allocating should use that slot
    int allocated = allocator.allocateInt();
    assertEquals(2, allocated, "Allocation should match peeked value");

    // And getNextSlot should now show next slot
    int peeked2 = allocator.getNextSlot();
    assertEquals(3, peeked2, "getNextSlot should advance after allocation");
  }

  @Test
  void testDoubleReleaseSameSlot() {
    // Given an allocator with an allocated slot
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    int slot = allocator.allocateInt();

    // When releasing the same slot twice
    allocator.release(slot);
    allocator.release(slot); // Should be no-op

    // Then only one reuse should be possible
    int reused = allocator.allocateInt();
    assertEquals(1, reused, "Should reuse freed slot once");

    int nextSlot = allocator.allocateInt();
    assertEquals(2, nextSlot, "Second allocation should use new slot");
  }

  // ============ NEW SCOPE-BASED TYPE SAFETY TESTS ============

  @Test
  void testScopedReuseAcrossTypes() {
    // Given an allocator that uses snapshot/restore for scope management
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);

    // Allocate an int slot in outer scope
    int intSlot = allocator.allocateInt();
    assertEquals(1, intSlot);

    // Enter inner scope
    LocalVariableAllocator.Snapshot innerScope = allocator.snapshot();

    // Release the int slot (now out of scope)
    allocator.release(intSlot);

    // Allocate a reference slot - should reuse same slot (type-safe because int is out of scope)
    int refSlot = allocator.allocateRef();
    assertEquals(1, refSlot, "Should reuse slot for different type after release");

    // Exit inner scope (restore)
    allocator.restore(innerScope);

    // Verify state is restored
    int newSlot = allocator.allocateInt();
    assertEquals(2, newSlot, "After restore, original int slot should still be allocated");
  }

  @Test
  void testNestedScopes() {
    // Given an allocator with nested scopes
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);

    // Outer scope
    int outerVar = allocator.allocateInt(); // slot 1
    LocalVariableAllocator.Snapshot outerSnapshot = allocator.snapshot();

    // Middle scope
    int middleVar = allocator.allocateInt(); // slot 2
    LocalVariableAllocator.Snapshot middleSnapshot = allocator.snapshot();

    // Inner scope
    int innerVar = allocator.allocateInt(); // slot 3

    // Exit inner scope
    allocator.restore(middleSnapshot);
    int afterInner = allocator.getNextSlot();
    assertEquals(3, afterInner, "After restoring middle snapshot, nextSlot should be 3");

    // Allocate again - should use slot 3
    int reusedInner = allocator.allocateInt();
    assertEquals(3, reusedInner);

    // Exit middle scope
    allocator.restore(outerSnapshot);
    int afterMiddle = allocator.getNextSlot();
    assertEquals(2, afterMiddle, "After restoring outer snapshot, nextSlot should be 2");

    // Allocate again - should use slot 2
    int reusedMiddle = allocator.allocateInt();
    assertEquals(2, reusedMiddle);
  }

  @Test
  void testScopeIsolation() {
    // Given an allocator
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);

    // Outer scope allocations
    int outer1 = allocator.allocateInt(); // slot 1
    int outer2 = allocator.allocateInt(); // slot 2

    // Enter inner scope
    LocalVariableAllocator.Snapshot snapshot = allocator.snapshot();

    // Inner scope allocations
    int inner1 = allocator.allocateInt(); // slot 3
    int inner2 = allocator.allocateInt(); // slot 4

    // Release inner slots
    allocator.release(inner1);
    allocator.release(inner2);

    // Verify inner slots are freed
    int reused1 = allocator.allocateInt();
    assertEquals(3, reused1, "Should reuse inner scope slot");

    // Exit inner scope
    allocator.restore(snapshot);

    // Verify outer scope is unchanged
    assertEquals(
        3, allocator.getNextSlot(), "Outer scope should not see inner allocations after restore");

    // Verify outer slots are still allocated
    int newOuter = allocator.allocateInt();
    assertEquals(3, newOuter, "Should allocate new slot, outer slots still in use");
  }

  @Test
  void testAllocateRefUsesFreeSingleSlots() {
    // Given an allocator with a freed single slot
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    int slot1 = allocator.allocateInt();
    allocator.release(slot1);

    // When allocating a reference type
    int refSlot = allocator.allocateRef();

    // Then it should reuse the freed slot (single-slot reuse enabled)
    assertEquals(1, refSlot, "allocateRef should reuse freed single slot");
  }

  @Test
  void testCreateChild() {
    // Given a parent allocator with some allocations
    LocalVariableAllocator parent = new LocalVariableAllocator(2);
    parent.allocateInt(); // slot 2
    parent.allocateInt(); // slot 3

    // When creating a child allocator
    LocalVariableAllocator child = parent.createChild();

    // Then child should start at parent's current frontier
    int childSlot = child.allocateInt();
    assertEquals(4, childSlot, "Child should start where parent left off");

    // And child allocations shouldn't affect parent
    int parentSlot = parent.allocateInt();
    assertEquals(4, parentSlot, "Parent should continue from its own position");
  }

  @Test
  void testAdvanceTo() {
    // Given an allocator
    LocalVariableAllocator allocator = new LocalVariableAllocator(1);
    allocator.allocateInt(); // slot 1

    // When advancing to a higher slot
    allocator.advanceTo(5);

    // Then next allocation should start at the advanced position
    int nextSlot = allocator.allocateInt();
    assertEquals(5, nextSlot, "Should allocate at advanced position");

    // When advancing to a lower slot (no-op)
    allocator.advanceTo(3);

    // Then it should not go backwards
    int afterNoOp = allocator.allocateInt();
    assertEquals(6, afterNoOp, "Should not go backwards");
  }
}
