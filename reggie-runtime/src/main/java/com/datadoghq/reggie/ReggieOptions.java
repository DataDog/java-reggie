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

import java.util.Objects;

/** Options for runtime Reggie compilation. */
public final class ReggieOptions {
  public static final ReggieOptions DEFAULT = builder().build();

  private final CapturePolicy capturePolicy;

  private ReggieOptions(Builder builder) {
    this.capturePolicy = Objects.requireNonNull(builder.capturePolicy, "capturePolicy");
  }

  public CapturePolicy capturePolicy() {
    return capturePolicy;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private CapturePolicy capturePolicy = CapturePolicy.ALL;

    private Builder() {}

    public Builder capturePolicy(CapturePolicy capturePolicy) {
      this.capturePolicy = Objects.requireNonNull(capturePolicy, "capturePolicy");
      return this;
    }

    public ReggieOptions build() {
      return new ReggieOptions(this);
    }
  }
}
