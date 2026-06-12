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

import java.util.EnumSet;

/** Options for runtime Reggie compilation. Toggles are expressed as {@link ReggieOption} flags. */
public final class ReggieOptions {
  public static final ReggieOptions DEFAULT = builder().build();

  private final EnumSet<ReggieOption> options;

  private ReggieOptions(Builder builder) {
    // EnumSet.copyOf requires a non-empty collection when given a plain Collection,
    // but the builder always passes an EnumSet (which carries the element type),
    // so the copy is always safe regardless of whether any flags are set.
    this.options =
        builder.options.isEmpty()
            ? EnumSet.noneOf(ReggieOption.class)
            : EnumSet.copyOf(builder.options);
  }

  /** Returns {@code true} if {@code option} is enabled. */
  public boolean has(ReggieOption option) {
    return options.contains(option);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final EnumSet<ReggieOption> options = EnumSet.noneOf(ReggieOption.class);

    private Builder() {}

    /** Enable one or more flags. */
    public Builder enable(ReggieOption... os) {
      for (ReggieOption o : os) {
        options.add(o);
      }
      return this;
    }

    /** Disable one or more flags. */
    public Builder disable(ReggieOption... os) {
      for (ReggieOption o : os) {
        options.remove(o);
      }
      return this;
    }

    /** Shortcut for {@code enable(CAPTURE_NAMED_ONLY)}. */
    public Builder namedOnly() {
      return enable(ReggieOption.CAPTURE_NAMED_ONLY);
    }

    /** Shortcut for {@code enable(ALLOW_JDK_FALLBACK)}. */
    public Builder allowJdkFallback() {
      return enable(ReggieOption.ALLOW_JDK_FALLBACK);
    }

    public ReggieOptions build() {
      return new ReggieOptions(this);
    }
  }
}
