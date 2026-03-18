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
 * Manages local variable slot allocation for bytecode generation with scope-based slot reuse.
 *
 * <p>Tracks which slots are in use and enables safe reuse of released slots through proper scope
 * management. Slot reuse is type-safe when previous variables are definitively out-of-scope,
 * achieved using the snapshot/restore pattern at scope boundaries.
 *
 * <h3>Usage Pattern for Type-Safe Reuse:</h3>
 *
 * <pre>{@code
 * // Before entering a scope (loop, conditional, block)
 * LocalVariableAllocator.Snapshot snapshot = allocator.snapshot();
 *
 * // Allocate and use variables within the scope
 * int tempVar = allocator.allocateInt();
 * int objVar = allocator.allocateRef();
 * // ... generate bytecode using these variables ...
 *
 * // When exiting the scope, restore to release all scope-local variables
 * allocator.restore(snapshot);
 * // Now tempVar and objVar slots can be reused for any type
 * }</pre>
 *
 * <p>Supports both single-slot types (int, float, object references) and double-slot types (long,
 * double). Method parameter slots are never reused to maintain JVM calling convention safety.
 */
public final class LocalVariableAllocator {
  private int nextSlot;
  private final BitSet allocated; // Track which slots are currently allocated
  private final BitSet doubleWidth; // Track which slots are first slot of long/double
  private final BitSet freeSingleSlots; // Available single-slots for reuse
  private final BitSet freeWideSlots; // Available wide-slot start positions
  private final int parameterBoundary; // Slots below this are never reused

  /**
   * Creates allocator with initial slot number (typically after method parameters). Slots below
   * startSlot are reserved for method parameters and will never be reused.
   *
   * @param startSlot first available slot number
   */
  public LocalVariableAllocator(int startSlot) {
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
   * Allocates a new slot for a reference type (Object, String, etc.). Uses first-fit allocation:
   * tries to reuse a freed slot first, otherwise allocates from frontier. Reference types occupy 1
   * slot.
   *
   * @return allocated slot number
   */
  public int allocateRef() {
    return allocateInt(); // Same logic for all single-slot types
  }

  /**
   * Allocates a new slot for a primitive type (int, char, etc.). Uses first-fit allocation: tries
   * to reuse a freed slot first, otherwise allocates from frontier. Most primitives occupy 1 slot.
   *
   * @return allocated slot number
   */
  public int allocateInt() {
    // Phase 1: Try to reuse a freed single-slot (only above parameterBoundary)
    int slot = freeSingleSlots.nextSetBit(parameterBoundary);
    if (slot >= 0) {
      freeSingleSlots.clear(slot);
      allocated.set(slot);
      return slot;
    }

    // Phase 2: Allocate from frontier
    slot = nextSlot++;
    allocated.set(slot);
    return slot;
  }

  /**
   * Allocates a new slot for a long or double (occupies 2 consecutive slots). Uses 3-phase search:
   * reuse freed wide slot, find consecutive free singles, or allocate from frontier.
   *
   * @return allocated slot number (the value occupies this slot and the next)
   */
  public int allocateLong() {
    // Phase 1: Try to reuse a freed wide-slot (only above parameterBoundary)
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

    // Phase 2: Try to find two consecutive free slots (starting from parameterBoundary)
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

    // Phase 3: No free consecutive slots, allocate new ones
    slot = nextSlot;
    nextSlot += 2;
    allocated.set(slot);
    allocated.set(slot + 1);
    doubleWidth.set(slot);
    return slot;
  }

  /**
   * Releases a variable back to the appropriate free list. Automatically detects whether the slot
   * is single or wide-slot. Slots above parameterBoundary are added to the appropriate free list
   * for reuse. This method is idempotent - safe to call multiple times on the same slot.
   *
   * @param slot the slot to release
   */
  public void release(int slot) {
    // Validation: ignore if already freed or never allocated
    if (!allocated.get(slot)) {
      return; // Idempotent - safe to call multiple times
    }

    // Safety: detect accidental release of second half of wide variable
    if (slot > 0 && doubleWidth.get(slot - 1)) {
      return; // Only release wide variables via first slot
    }

    if (doubleWidth.get(slot)) {
      // This is a double-width variable
      releaseWideSlot(slot);
    } else {
      // Single-slot variable
      releaseSingleSlot(slot);
    }
  }

  /**
   * Releases multiple consecutive slots back to the free-list. Delegates to single-slot release()
   * to ensure proper free list management.
   *
   * @param slot the first slot to release
   * @param count number of slots to release
   */
  public void release(int slot, int count) {
    for (int i = 0; i < count; i++) {
      release(slot + i);
    }
  }

  /** Release a wide-slot variable (long/double). */
  private void releaseWideSlot(int slot) {
    // Mark both slots as free
    allocated.clear(slot);
    allocated.clear(slot + 1);
    doubleWidth.clear(slot);

    // Add to wide free list ONLY if above parameter boundary
    if (slot >= parameterBoundary) {
      freeWideSlots.set(slot);
    }

    // Invariant maintenance: Clean up single free lists
    // (Both slots should not appear in single free list)
    freeSingleSlots.clear(slot);
    freeSingleSlots.clear(slot + 1);
  }

  /** Release a single-slot variable. */
  private void releaseSingleSlot(int slot) {
    // Mark slot as free
    allocated.clear(slot);

    // Add to single free list ONLY if above parameter boundary
    if (slot >= parameterBoundary) {
      freeSingleSlots.set(slot);
    }

    // Critical: Update wide-slot eligibility
    // This slot can no longer START a wide allocation
    freeWideSlots.clear(slot);

    // If slot-1 was a potential wide slot, it's no longer valid
    // (because slot-1 would need BOTH slot-1 and slot to be free)
    if (slot > 0) {
      freeWideSlots.clear(slot - 1);
    }
  }

  /**
   * Returns the next available slot number without allocating.
   *
   * @return next slot that will be allocated
   */
  public int getNextSlot() {
    return nextSlot;
  }

  /**
   * Reserves a range of slots for later use (e.g., for sub-methods).
   *
   * @param count number of slots to reserve
   * @return starting slot number of reserved range
   */
  public int reserve(int count) {
    int start = nextSlot;
    nextSlot += count;
    for (int i = 0; i < count; i++) {
      allocated.set(start + i);
    }
    return start;
  }

  /**
   * Reserves a specific slot number, ensuring nextSlot is at least slotNumber + 1. Useful when
   * external code has already allocated specific slots. Removes the slot from free lists to
   * maintain consistency.
   *
   * @param slotNumber the specific slot to reserve
   */
  public void reserveSlot(int slotNumber) {
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
   * Creates a child allocator that starts at the current position. Useful for nested scopes that
   * need independent allocation tracking. The child inherits the current frontier but no free lists
   * (intentionally isolated).
   *
   * @return new allocator starting at current position
   */
  public LocalVariableAllocator createChild() {
    return new LocalVariableAllocator(nextSlot);
  }

  /**
   * Advances the allocator to ensure it's at least at the given slot. Useful for synchronizing with
   * child allocators.
   *
   * @param minSlot minimum slot number to advance to
   */
  public void advanceTo(int minSlot) {
    if (nextSlot < minSlot) {
      nextSlot = minSlot;
    }
  }

  /**
   * Creates a snapshot of the current allocation state. Useful for implementing scopes: snapshot
   * before entering a scope, then restore when exiting to automatically release all scope-local
   * variables.
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
   * Restores allocation state from a previous snapshot. This effectively releases all slots
   * allocated after the snapshot was taken, making them available for reuse.
   *
   * @param snapshot the snapshot to restore to
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

  /**
   * Immutable snapshot of allocator state. Used for implementing scope-based variable lifetime
   * management.
   */
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
