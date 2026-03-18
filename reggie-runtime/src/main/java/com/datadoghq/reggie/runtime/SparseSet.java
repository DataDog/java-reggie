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

/**
 * Sparse set data structure with O(1) clear, add, and contains operations. Based on Briggs &
 * Torczon algorithm.
 *
 * <p>Space: O(2N) for dense/sparse arrays Time: O(1) for all operations including clear
 *
 * <p>This is significantly faster than BitSet for small sets where clear() is frequent.
 */
public final class SparseSet {
  private final int[] dense; // Elements in insertion order
  private final int[] sparse; // sparse[x] = index in dense array
  private int size; // Number of elements currently in set

  /** Create a sparse set for elements in range [0, capacity). */
  public SparseSet(int capacity) {
    this.dense = new int[capacity];
    this.sparse = new int[capacity];
    this.size = 0;
  }

  /** Clear the set in O(1) time. */
  public void clear() {
    size = 0;
  }

  /**
   * Add element x to the set in O(1) time. No-op if already present. Inlined contains check to
   * avoid method call overhead.
   */
  public void add(int x) {
    int idx = sparse[x]; // Single array access, store in local
    if (idx >= size || dense[idx] != x) { // Reuse local variable
      dense[size] = x;
      sparse[x] = size;
      size++;
    }
  }

  /** Check if element x is in the set in O(1) time. */
  public boolean contains(int x) {
    int idx = sparse[x];
    return idx < size && dense[idx] == x;
  }

  /** Get current size of the set. */
  public int size() {
    return size;
  }

  /** Check if set is empty in O(1) time. */
  public boolean isEmpty() {
    return size == 0;
  }

  /** Get element at index i (for iteration). Valid for i in [0, size()). */
  public int get(int i) {
    return dense[i];
  }
}
