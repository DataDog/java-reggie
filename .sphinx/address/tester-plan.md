# Tester Plan

## Test file 1: reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/LazyDFABytecodeGeneratorTest.java

Add to the existing class (pattern constant `LARGE_NFA_PATTERN = "(?:a+b+|b+a+){75}"` already present):

### Test: testMatchMethod
- Get a `ReggieMatcher` via `RuntimeCompiler.compile(LARGE_NFA_PATTERN)`.
- Reflectively invoke `match(String)` with `"ab".repeat(75)`.
- Assert result is non-null.
- Reflectively call `result.start(0)` and assert == 0.
- Reflectively call `result.end(0)` and assert == 150.
- Invoke `match("ab".repeat(74))` and assert result is null (no match).

### Test: testMatchBoundedMethod
- Get a `ReggieMatcher` via `RuntimeCompiler.compile(LARGE_NFA_PATTERN)`.
- Input: `"xx" + "ab".repeat(75)` (length = 2 + 150 = 152).
- Reflectively invoke `matchBounded(String, int, int)` with input, start=2, end=152.
- Assert result is non-null.
- Reflectively call `result.start(0)` and assert == 2.
- Reflectively call `result.end(0)` and assert == 152.
- Also test: `matchBounded(input, 0, 152)` → null (substring `"xx" + "ab".repeat(75)` does
  not match the pattern alone since it starts with "xx").
  Actually: start=0, end=152 means substring is the full string which starts with "xx" — no match.

### Test: testFindMatchFromMethod
- Get a `ReggieMatcher` via `RuntimeCompiler.compile(LARGE_NFA_PATTERN)`.
- Input: `"xx" + "ab".repeat(75) + "yy"` (length = 154).
- Reflectively invoke `findMatchFrom(String, int)` with input, fromIndex=0.
- Assert result is non-null.
- Reflectively call `result.start(0)` and assert == 2.
- Reflectively call `result.end(0)` and assert == 152.
- Also test with input `"xxxx"` — assert result is null.

### How to use MatchResult reflectively
```java
Object result = method.invoke(matcher, args...);
if (result != null) {
    Method start = result.getClass().getMethod("start", int.class);
    Method end   = result.getClass().getMethod("end",   int.class);
    assertEquals(expectedStart, start.invoke(result, 0));
    assertEquals(expectedEnd,   end.invoke(result, 0));
}
```

---

## Test file 2: reggie-processor/src/test/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGeneratorTest.java

Add a new `@Test` method `testLazyDfaStrategy` to the existing class.

The existing `compile()` helper takes `(String pattern, String className)` and returns an
`Object` instance. Use:
```java
Object matcher = compile("(?:a+b+|b+a+){75}", "LazyDfaMatcher");
```

### Assertions
- `matches("ab".repeat(75))` → true
- `matches("ab".repeat(74) + "b")` → false (74 complete ab groups then extra b, no 75th a)
- `find("xx" + "ab".repeat(75) + "yy")` → true
- `find("xx")` → false

### How to invoke
```java
Method matches = matcher.getClass().getMethod("matches", String.class);
Method find    = matcher.getClass().getMethod("find",    String.class);
assertTrue((Boolean)  matches.invoke(matcher, "ab".repeat(75)));
assertFalse((Boolean) matches.invoke(matcher, "ab".repeat(74) + "b"));
assertTrue((Boolean)  find.invoke(matcher, "xx" + "ab".repeat(75) + "yy"));
assertFalse((Boolean) find.invoke(matcher, "xx"));
```
