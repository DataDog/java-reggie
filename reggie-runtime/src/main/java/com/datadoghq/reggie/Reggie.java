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
package com.datadoghq.reggie;

import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Main entry point for Reggie - hybrid compile-time and runtime regex library.
 *
 * <p><b>Compile-time patterns:</b> Use {@link #patterns(Class)} to access generated regex pattern
 * implementations via {@link ServiceLoader}.
 *
 * <p>Pattern provider classes should be abstract classes that implement {@link ReggiePatterns} and
 * have methods annotated with {@code @RegexPattern}. The annotation processor generates an
 * implementation class and registers it as a service provider.
 *
 * <p>Example:
 *
 * <pre>
 * public abstract class MyPatterns implements ReggiePatterns {
 *     {@literal @}RegexPattern("\\d+")
 *     public abstract ReggieMatcher digits();
 *
 *     {@literal @}RegexPattern("[a-z]+")
 *     public abstract ReggieMatcher lowercase();
 * }
 *
 * // Usage:
 * MyPatterns patterns = Reggie.patterns(MyPatterns.class);
 * boolean hasDigits = patterns.digits().find("abc123");
 * </pre>
 *
 * <p><b>Runtime patterns:</b> Use {@link #compile(String)} to compile patterns at runtime with
 * automatic caching.
 *
 * <p>Example:
 *
 * <pre>
 * ReggieMatcher phone = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
 * boolean valid = phone.matches("123-456-7890");
 * </pre>
 */
public final class Reggie {

  private Reggie() {
    // Utility class, no instantiation
  }

  /**
   * Get an instance of the pattern provider class. Uses {@link ServiceLoader} to find and
   * instantiate the generated implementation.
   *
   * @param patternClass The pattern provider class
   * @param <T> The pattern provider type
   * @return An instance of the pattern provider implementation
   * @throws IllegalArgumentException if no service provider is found
   */
  public static <T extends ReggiePatterns> T patterns(Class<T> patternClass) {
    ServiceLoader<T> loader = ServiceLoader.load(patternClass);

    for (T provider : loader) {
      // Return the first (and should be only) provider
      return provider;
    }

    throw new IllegalArgumentException(
        "No service provider found for "
            + patternClass.getName()
            + ". Make sure the class has @RegexPattern annotated methods and has been compiled.");
  }

  // ==================== Runtime Pattern Compilation API ====================

  /**
   * Compile a regex pattern at runtime with automatic caching. The pattern is compiled lazily on
   * first use and cached for subsequent calls. Thread-safe: uses {@code
   * ConcurrentHashMap.computeIfAbsent} to avoid duplicate compilation.
   *
   * <p>Example:
   *
   * <pre>
   * ReggieMatcher matcher = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
   * boolean valid = matcher.matches("123-456-7890");
   * </pre>
   *
   * @param pattern the regex pattern string
   * @return compiled matcher instance
   * @throws java.util.regex.PatternSyntaxException if pattern is invalid
   */
  public static ReggieMatcher compile(String pattern) {
    return RuntimeCompiler.compile(pattern);
  }

  /**
   * Compile a regex pattern with an explicit cache key. Useful for user-controlled caching when you
   * want the same compiled matcher for different pattern strings, or need to explicitly manage
   * cache keys.
   *
   * <p>Example:
   *
   * <pre>
   * String userPattern = getUserInput();
   * ReggieMatcher matcher = Reggie.cached("user-search", userPattern);
   * </pre>
   *
   * @param key custom cache key
   * @param pattern the regex pattern string
   * @return compiled matcher instance
   * @throws java.util.regex.PatternSyntaxException if pattern is invalid
   */
  public static ReggieMatcher cached(String key, String pattern) {
    return RuntimeCompiler.cached(key, pattern);
  }

  /**
   * Clear the entire runtime pattern cache. This removes all cached compiled patterns, freeing
   * memory. Future calls to {@link #compile(String)} or {@link #cached(String, String)} will
   * recompile patterns from scratch.
   */
  public static void clearCache() {
    RuntimeCompiler.clearCache();
  }

  /**
   * Get the current number of cached runtime patterns.
   *
   * @return number of patterns in the cache
   */
  public static int cacheSize() {
    return RuntimeCompiler.cacheSize();
  }

  /**
   * Get all cached pattern keys (for debugging/monitoring).
   *
   * @return set of cache keys (pattern strings or custom keys)
   */
  public static Set<String> cachedPatterns() {
    return RuntimeCompiler.cachedPatterns();
  }
}
