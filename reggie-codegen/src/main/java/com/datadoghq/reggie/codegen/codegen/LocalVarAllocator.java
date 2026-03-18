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

import java.util.BitSet;

/**
 * Helper class to track local variable slot allocation during bytecode generation. Prevents slot
 * conflicts by maintaining a counter of the next available slot and a free-list for reusing
 * released slots.
 *
 * <p>Supports both single-slot types (int, float, object references) and double-slot types (long,
 * double).
 */
public class LocalVarAllocator {
  private int nextSlot;
  private final BitSet allocated; // Track which slots are currently allocated
  private final BitSet doubleWidth; // Track which slots are first slot of long/double
  private final BitSet freeSingleSlots; // Available single-slots for reuse
  private final BitSet freeWideSlots; // Available wide-slot start positions
  private final int parameterBoundary; // Slots below this are never reused

  /**
   * Create allocator starting from the given slot number. Typically initialized with the number of
   * method parameters + 1 for 'this'.
   */
  public LocalVarAllocator(int startSlot) {
    this.nextSlot = startSlot;
    this.parameterBoundary = startSlot;
    this.allocated = new BitSet();
    this.doubleWidth = new BitSet();
    this.freeSingleSlots = new BitSet();
    this.freeWideSlots = new BitSet();
    // Mark slots before startSlot as allocated (method parameters)
    for (int i = 0; i < startSlot; i++) {
      allocated.set(i);
    }
  }

  /**
   * Allocate a new single-slot local variable (int, float, object reference). Always allocates
   * sequentially to avoid JVM verifier type conflicts when slots are reused across different code
   * paths in the same method. TODO: Enable reuse when we have proper liveness analysis to ensure
   * safety.
   *
   * @return the allocated slot number
   */
  public int allocate() {
    // Always allocate sequentially (don't reuse) to avoid JVM verifier type conflicts
    // when slots are reused across different code paths in the same method.
    // The free list infrastructure is kept for future use with liveness analysis.
    int slot = nextSlot++;
    allocated.set(slot);
    return slot;
  }

  /**
   * Allocate a long variable (2 consecutive JVM slots). Alias for allocateWide() for clarity when
   * allocating longs.
   *
   * @return the first slot number (variable occupies slot and slot+1)
   */
  public int allocateLong() {
    return allocateWide();
  }

  /**
   * Allocate a new double-slot local variable (long, double). Attempts to reuse freed wide-slots
   * above parameterBoundary first, then searches for consecutive free slots, otherwise allocates
   * sequentially.
   *
   * @return the first slot number (variable occupies slot and slot+1)
   */
  public int allocateWide() {
    // Try to reuse a freed wide-slot (only slots above parameterBoundary)
    int slot = freeWideSlots.nextSetBit(parameterBoundary);
    if (slot >= 0) {
      // Defensive validation: ensure both slots are actually free
      if (!allocated.get(slot) && !allocated.get(slot + 1)) {
        freeWideSlots.clear(slot);
        allocated.set(slot);
        allocated.set(slot + 1);
        doubleWidth.set(slot);
        return slot;
      }
      // Inconsistent state, clear from free list and continue
      freeWideSlots.clear(slot);
    }

    // Try to find two consecutive free slots (starting from parameterBoundary)
    for (int i = allocated.nextClearBit(parameterBoundary);
        i < nextSlot;
        i = allocated.nextClearBit(i + 1)) {
      // Make sure this slot isn't the second half of a double-width var
      if (i > 0 && doubleWidth.get(i - 1)) {
        continue;
      }
      // Check if next slot is also free
      if (!allocated.get(i + 1) && !doubleWidth.get(i + 1)) {
        allocated.set(i);
        allocated.set(i + 1);
        doubleWidth.set(i);
        // Clean up single-slot free lists (these slots are now part of a wide allocation)
        freeSingleSlots.clear(i);
        freeSingleSlots.clear(i + 1);
        return i;
      }
    }

    // No free consecutive slots, allocate new ones
    slot = nextSlot;
    nextSlot += 2;
    allocated.set(slot);
    allocated.set(slot + 1);
    doubleWidth.set(slot);
    return slot;
  }

  /**
   * Allocate multiple consecutive slots.
   *
   * @param count number of slots to allocate
   * @return the first allocated slot number
   */
  public int allocate(int count) {
    if (count == 1) {
      return allocate();
    } else if (count == 2) {
      return allocateWide();
    }

    // For count > 2, allocate consecutive slots (rare case, e.g., arrays)
    // Try to find 'count' consecutive free slots
    outer:
    for (int i = allocated.nextClearBit(0); i < nextSlot; i = allocated.nextClearBit(i + 1)) {
      // Check if we have 'count' consecutive free slots starting at i
      for (int j = 0; j < count; j++) {
        if (allocated.get(i + j) || (i + j > 0 && doubleWidth.get(i + j - 1))) {
          continue outer;
        }
      }
      // Found consecutive free slots
      for (int j = 0; j < count; j++) {
        allocated.set(i + j);
      }
      return i;
    }

    // No free consecutive slots, allocate new ones
    int slot = nextSlot;
    nextSlot += count;
    for (int j = 0; j < count; j++) {
      allocated.set(slot + j);
    }
    return slot;
  }

  /**
   * Release a variable back to the appropriate free list. Handles both single-slot and wide-slot
   * variables. Slots above parameterBoundary are added to the appropriate free list for reuse.
   *
   * @param slot the slot to release
   */
  public void release(int slot) {
    // Validate slot is actually allocated
    if (!allocated.get(slot)) {
      return; // Already freed or never allocated
    }

    // Edge case: trying to release second slot of a wide variable
    if (slot > 0 && doubleWidth.get(slot - 1)) {
      return; // Only release via first slot
    }

    if (doubleWidth.get(slot)) {
      // This is a double-width variable
      allocated.clear(slot);
      allocated.clear(slot + 1);
      doubleWidth.clear(slot);
      // Add to wide free list if above parameter boundary
      if (slot >= parameterBoundary) {
        freeWideSlots.set(slot);
      }
      // Clean up single free lists (both slots should not be singles)
      freeSingleSlots.clear(slot);
      freeSingleSlots.clear(slot + 1);
    } else {
      // Single-slot variable
      allocated.clear(slot);
      // Add to single free list if above parameter boundary
      if (slot >= parameterBoundary) {
        freeSingleSlots.set(slot);
      }
      // Clean up wide free list: this slot can't start a wide slot anymore,
      // and if slot-1 was a wide slot start, it's no longer valid
      freeWideSlots.clear(slot);
      if (slot > 0) {
        freeWideSlots.clear(slot - 1);
      }
    }
  }

  /**
   * Release multiple consecutive slots back to the free-list. Delegates to single-slot release() to
   * ensure proper free list management.
   *
   * @param slot the first slot to release
   * @param count number of slots to release
   */
  public void release(int slot, int count) {
    for (int i = 0; i < count; i++) {
      release(slot + i);
    }
  }

  /** Get the next available slot without allocating it. */
  public int peek() {
    return nextSlot;
  }

  /**
   * Reserve a specific slot number, ensuring nextSlot is at least slotNumber + 1. Useful when
   * external code has already allocated specific slots. Removes the slot from free lists to
   * maintain consistency.
   */
  public void reserve(int slotNumber) {
    if (slotNumber >= nextSlot) {
      nextSlot = slotNumber + 1;
    }
    allocated.set(slotNumber);
    // Remove from both free lists to maintain consistency
    freeSingleSlots.clear(slotNumber);
    freeWideSlots.clear(slotNumber);
    if (slotNumber > 0) {
      freeWideSlots.clear(slotNumber - 1);
    }
  }

  /**
   * Create a snapshot of the current allocation state. Useful for nested scopes where you want to
   * restore state later.
   *
   * @return snapshot token that can be passed to restore()
   */
  public Snapshot snapshot() {
    return new Snapshot(
        nextSlot,
        (BitSet) allocated.clone(),
        (BitSet) doubleWidth.clone(),
        (BitSet) freeSingleSlots.clone(),
        (BitSet) freeWideSlots.clone());
  }

  /**
   * Restore allocation state from a previous snapshot. This effectively releases all slots
   * allocated after the snapshot was taken.
   */
  public void restore(Snapshot snapshot) {
    this.nextSlot = snapshot.nextSlot;
    this.allocated.clear();
    this.allocated.or(snapshot.allocated);
    this.doubleWidth.clear();
    this.doubleWidth.or(snapshot.doubleWidth);
    this.freeSingleSlots.clear();
    this.freeSingleSlots.or(snapshot.freeSingleSlots);
    this.freeWideSlots.clear();
    this.freeWideSlots.or(snapshot.freeWideSlots);
  }

  /** Immutable snapshot of allocator state. */
  public static class Snapshot {
    private final int nextSlot;
    private final BitSet allocated;
    private final BitSet doubleWidth;
    private final BitSet freeSingleSlots;
    private final BitSet freeWideSlots;

    private Snapshot(
        int nextSlot,
        BitSet allocated,
        BitSet doubleWidth,
        BitSet freeSingleSlots,
        BitSet freeWideSlots) {
      this.nextSlot = nextSlot;
      this.allocated = allocated;
      this.doubleWidth = doubleWidth;
      this.freeSingleSlots = freeSingleSlots;
      this.freeWideSlots = freeWideSlots;
    }
  }
}
