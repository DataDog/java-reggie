# Coder Plan

## File 1: reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/LazyDFACache.java

### Step 1 — Add INT_ARRAY_VH field
After the existing `TABLES_VH` declaration, add:
```java
static final VarHandle INT_ARRAY_VH;
```
In the `static {}` block, after the `TABLES_VH` assignment, add:
```java
INT_ARRAY_VH = MethodHandles.arrayElementVarHandle(int[].class);
```

### Step 2 — Fix `matches()` hot loop: replace plain IALOAD with getAcquire
In the `matches()` method, the line:
```java
int next = (table != null && c < 128) ? table[c] : UNCACHED;
```
Must become:
```java
int next = (table != null && c < 128) ? (int) INT_ARRAY_VH.getAcquire(table, c) : UNCACHED;
```

### Step 3 — Fix `cacheEntry()` else-branch: replace plain store with setRelease
In `cacheEntry`, the else-branch:
```java
} else {
    table[c] = value; // idempotent: same key always maps to same value
}
```
Must become:
```java
} else {
    INT_ARRAY_VH.setRelease(table, c, value); // idempotent; release ensures visibility on ARM
}
```

### Verify: compile
```
./gradlew :reggie-runtime:compileJava --no-daemon
```

---

## File 2: reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/LazyDFABytecodeGenerator.java

### Step 4 — Update generateMatchesMethod: replace IALOAD with VarHandle getAcquire
Find the section that reads `table[c]`:
```java
mv.visitVarInsn(ALOAD, 7);
mv.visitVarInsn(ILOAD, 6);
mv.visitInsn(IALOAD); // table[c]
mv.visitVarInsn(ISTORE, 8);
```
Replace with VarHandle getAcquire pattern:
```java
mv.visitFieldInsn(GETSTATIC, LAZY_CACHE, "INT_ARRAY_VH", "Ljava/lang/invoke/VarHandle;");
mv.visitVarInsn(ALOAD, 7);  // table
mv.visitVarInsn(ILOAD, 6);  // c
mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "getAcquire", "([II)I", false);
mv.visitVarInsn(ISTORE, 8);
```
This is inside the fast-path branch (after the `slowPath`/`afterTableRead` label), right
after the null-check and c<128 guard.

### Verify: compile
```
./gradlew :reggie-runtime:compileJava :reggie-codegen:compileJava --no-daemon
```

---

## File 3: reggie-benchmark/src/main/java/com/datadoghq/reggie/benchmark/LazyDFABenchmark.java

### Step 5 — Change FrozenState warm-up alphabet from 36-char to "ab"
In `FrozenState.setup()`, change:
```java
String alpha = "abcdefghijklmnopqrstuvwxyz0123456789";
```
to:
```java
String alpha = "ab";
```
Update the comment above the warm-up loop to explain the intent:
```java
// Use only 'a'/'b' so every warm-up step forces a real NFA-derived DFA transition;
// random 36-char strings hit DEAD after one step and add too few states to fill the cap.
```

---

## File 4: docs/superpowers/specs/2026-05-28-lazy-dfa-design.md

### Step 6 — Fix "c & 0x7F" to accurate description
Find all occurrences of "c & 0x7F" (likely one or two in the R2 description). Replace with
accurate language: the ASCII table covers `c < 128` only; characters with `c >= 128` bypass
the table and fall through to the NFA step. Example replacement:
- "indexed by `c & 0x7F`" → "covers ASCII only (`c < 128`); non-ASCII characters (`c ≥ 128`) bypass the table and fall through to the NFA step"
