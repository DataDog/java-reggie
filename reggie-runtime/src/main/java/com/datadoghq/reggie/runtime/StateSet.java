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

import java.util.BitSet;

/**
 * Hybrid state set optimized for NFA simulation. Uses BitSet for small state counts (≤64) and
 * SparseSet for larger ones.
 *
 * <p>For ≤64 states, BitSet uses a single long internally with bit operations. For >64 states,
 * SparseSet provides O(1) operations with better cache behavior.
 */
public final class StateSet {
  private static final int BITSET_THRESHOLD = 64;

  private final int capacity;
  private final boolean useBitSet;

  // BitSet path (for ≤64 states)
  private BitSet bitSet;

  // SparseSet path (for >64 states)
  private SparseSet sparseSet;

  /**
   * Stateful iterator for efficient sequential iteration. Avoids repeated scanning by maintaining
   * cursor position.
   */
  public static final class Iterator {
    private final StateSet stateSet;

    // For BitSet: SWAR iteration state
    private long remainingBits; // Remaining bits to scan in current word
    private int baseOffset; // Base bit offset for current word

    // For SparseSet: index-based iteration state
    private int sparseIndex; // Current index in dense array

    private boolean initialized;

    Iterator(StateSet stateSet) {
      this.stateSet = stateSet;
      this.initialized = false;
    }

    /**
     * Initialize or reset the iterator to start from beginning. Must be called before first
     * nextSetBit().
     */
    public void reset() {
      if (stateSet.useBitSet) {
        // Extract BitSet's internal long word using toLongArray()
        // For ≤64 bits, this returns a single-element array
        long[] words = stateSet.bitSet.toLongArray();
        this.remainingBits = (words.length > 0) ? words[0] : 0L;
        this.baseOffset = 0;
      } else {
        this.sparseIndex = 0;
      }
      this.initialized = true;
    }

    /**
     * Get next set bit using SWAR for BitSet or index iteration for SparseSet. Returns -1 when no
     * more bits are set.
     *
     * <p>Uses Long.numberOfTrailingZeros() for O(1) bit scanning instead of linear scanning through
     * all bit positions.
     */
    public int nextSetBit() {
      if (!initialized) {
        reset();
      }

      if (stateSet.useBitSet) {
        // SWAR: Find next set bit in remaining bits
        if (remainingBits == 0) {
          return -1; // No more set bits
        }

        // Extract lowest set bit using SWAR
        int trailingZeros = Long.numberOfTrailingZeros(remainingBits);
        int bitIndex = baseOffset + trailingZeros;

        // Clear the bit we just found (avoids re-finding it)
        remainingBits &= (remainingBits - 1); // Clear lowest set bit

        return bitIndex;
      } else {
        // SparseSet: Simple index iteration
        if (sparseIndex >= stateSet.sparseSet.size()) {
          return -1;
        }
        return stateSet.sparseSet.get(sparseIndex++);
      }
    }

    /** Check if there are more bits to iterate. */
    public boolean hasNext() {
      if (!initialized) {
        reset();
      }

      if (stateSet.useBitSet) {
        return remainingBits != 0;
      } else {
        return sparseIndex < stateSet.sparseSet.size();
      }
    }
  }

  /**
   * Create a stateful iterator for efficient sequential iteration. The iterator maintains cursor
   * position to avoid repeated scanning.
   *
   * <p>Usage:
   *
   * <pre>
   * StateSet.Iterator iter = stateSet.iterator();
   * iter.reset();
   * int state;
   * while ((state = iter.nextSetBit()) >= 0) {
   *     // process state
   * }
   * </pre>
   */
  public Iterator iterator() {
    return new Iterator(this);
  }

  /** Create a state set for elements in range [0, capacity). */
  public StateSet(int capacity) {
    this.capacity = capacity;
    this.useBitSet = capacity <= BITSET_THRESHOLD;

    if (useBitSet) {
      this.bitSet = new BitSet(capacity);
    } else {
      this.sparseSet = new SparseSet(capacity);
    }
  }

  /** Clear the set in O(1) time. */
  public void clear() {
    if (useBitSet) {
      bitSet.clear();
    } else {
      sparseSet.clear();
    }
  }

  /** Add element x to the set. No-op if already present. */
  public void add(int x) {
    if (useBitSet) {
      bitSet.set(x);
    } else {
      sparseSet.add(x);
    }
  }

  /** Check if element x is in the set. */
  public boolean contains(int x) {
    if (useBitSet) {
      return bitSet.get(x);
    } else {
      return sparseSet.contains(x);
    }
  }

  /** Get current size of the set. */
  public int size() {
    if (useBitSet) {
      return bitSet.cardinality();
    } else {
      return sparseSet.size();
    }
  }

  /** Check if set is empty. */
  public boolean isEmpty() {
    if (useBitSet) {
      return bitSet.isEmpty();
    } else {
      return sparseSet.isEmpty();
    }
  }

  /**
   * Get element at index i (for iteration over sparse set only). Valid for i in [0, size()).
   *
   * <p>Note: BitSet iteration uses nextSetBit() instead.
   */
  public int get(int i) {
    if (useBitSet) {
      // For BitSet, iterate using nextSetBit
      int bit = bitSet.nextSetBit(0);
      for (int j = 0; j < i && bit >= 0; j++) {
        bit = bitSet.nextSetBit(bit + 1);
      }
      return bit;
    } else {
      return sparseSet.get(i);
    }
  }

  /**
   * Get the next set bit starting from fromIndex (inclusive). Returns -1 if no set bit found.
   * Optimized for BitSet iteration.
   */
  public int nextSetBit(int fromIndex) {
    if (useBitSet) {
      return bitSet.nextSetBit(fromIndex);
    } else {
      // For SparseSet, linear scan through dense array
      for (int i = 0; i < sparseSet.size(); i++) {
        int elem = sparseSet.get(i);
        if (elem >= fromIndex) {
          return elem;
        }
      }
      return -1;
    }
  }
}
