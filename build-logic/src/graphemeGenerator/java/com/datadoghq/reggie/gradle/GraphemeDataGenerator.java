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
package com.datadoghq.reggie.gradle;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.tools.ToolProvider;

/**
 * Regenerates factual property deltas from audited GA JDKs. Internal JDK access occurs only here.
 *
 * <p>Run from the checkout root with the pinned JDK 22 Unicode file. Gradle selects JDKs 21, 25, 26
 * and 27 through its toolchains:
 *
 * <pre>
 * ./gradlew :reggie-runtime:generateGraphemeData -PgraphemeUnicodeData=/path/to/DerivedCoreProperties.txt
 * ./gradlew spotlessApply
 * </pre>
 *
 * <p>The Unicode input comes from
 * https://raw.githubusercontent.com/openjdk/jdk/jdk-22%2B36/src/java.base/share/data/unicodedata/DerivedCoreProperties.txt.
 * Its checksum is validated. Its licence is retained in runtime META-INF/LICENSE-Unicode.txt.
 * Source audit establishes identical classifier corrections for JDKs 21-23, 24-25 and 26-27. JDK
 * 22-23 Indic properties come from Unicode 15.1; the other profiles are extracted from installed
 * JDKs. The generator checks every code point before and after writing.
 */
public class GraphemeDataGenerator {
  private static final int CODE_POINTS = Character.MAX_CODE_POINT + 1;
  private static final String BEGIN = "  // BEGIN GENERATED DATA\n";
  private static final String END = "  // END GENERATED DATA";

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--profile")) {
      extract(Path.of(args[1]), Path.of(args[2]), args[3], Integer.parseInt(args[4]));
      return;
    }
    if (args.length > 0 && args[0].equals("--check")) {
      check(Path.of(args[1]));
      return;
    }
    if (args.length != 7) {
      throw new IllegalArgumentException(
          "Expected JDK21_HOME JDK25_HOME JDK26_HOME JDK27_HOME DerivedCoreProperties.txt target work");
    }
    String hash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[4]))));
    if (!hash.equals("39295118c4c32768a5c071850fba66d0789ab5f7967eaeffee86294b6901ce5c")) {
      throw new IllegalArgumentException(
          "Unexpected JDK 22 DerivedCoreProperties.txt checksum: " + hash);
    }
    Path target = Path.of(args[5]);
    Path work = Path.of(args[6]);
    compile(target, work);
    int[] features = {21, 25, 26, 27};
    List<int[][]> profiles = new ArrayList<>();
    for (int i = 0; i < features.length; i++) {
      Path data = work.resolve("profile-" + features[i]);
      run(args[i], "--profile", work.toString(), data.toString(), "-", "" + features[i]);
      profiles.add(read(data));
    }
    Path indic15 = work.resolve("indic-15.1");
    run(args[0], "--profile", work.toString(), indic15.toString(), args[4], "21");
    if (!Arrays.deepEquals(profiles.get(2), profiles.get(3))) {
      throw new IllegalStateException(
          "JDK 26 and 27 property profiles differ; audit before updating");
    }
    StringBuilder generated = new StringBuilder();
    generated.append(
        "  // Generated properties relative to Character; see the generator for provenance.\n");
    append(generated, "TYPES_15", profiles.get(0)[0]);
    append(generated, "TYPES_16", profiles.get(1)[0]);
    append(generated, "TYPES_17", profiles.get(2)[0]);
    append(generated, "INDIC_15_1", read(indic15)[1]);
    append(generated, "INDIC_16", profiles.get(1)[1]);
    append(generated, "INDIC_17", profiles.get(2)[1]);
    String source = Files.readString(target);
    int start = source.indexOf(BEGIN);
    int end = source.indexOf(END);
    if (start < 0 || end <= start) {
      throw new IllegalStateException("Generated data markers are missing");
    }
    String updated =
        source.substring(0, start + BEGIN.length()) + generated + source.substring(end);
    Path candidate =
        Files.createDirectories(work.resolve("generated")).resolve("GraphemeData.java");
    Files.writeString(candidate, updated);
    compile(candidate, work);
    for (int i = 0; i < features.length; i++) {
      run(args[i], "--check", work.toString());
    }
    Files.writeString(target, updated);
    System.out.println("Generated and verified " + target);
  }

  private static void compile(Path source, Path output) {
    int result =
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "--release", "21", "-d", output.toString(), source.toString());
    if (result != 0) {
      throw new IllegalStateException("Could not compile GraphemeData");
    }
  }

  private static void run(String jdk, String... args) throws Exception {
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(jdk, "bin/java").toString(),
                "--add-opens=java.base/jdk.internal.util.regex=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                GraphemeDataGenerator.class.getName()));
    command.addAll(List.of(args));
    if (new ProcessBuilder(command).inheritIO().start().waitFor() != 0) {
      throw new IllegalStateException("Profile extraction or verification failed: " + jdk);
    }
  }

  private static Class<?> dataClass(Path classes) throws Exception {
    return new URLClassLoader(new URL[] {classes.toUri().toURL()})
        .loadClass("com.datadoghq.reggie.runtime.GraphemeData");
  }

  private static Method method(Class<?> type, String name) throws Exception {
    Method method = type.getDeclaredMethod(name, int.class);
    method.setAccessible(true);
    return method;
  }

  private static void extract(Path classes, Path output, String ucd, int feature) throws Exception {
    if (Runtime.version().feature() != feature || Runtime.version().pre().isPresent()) {
      throw new IllegalArgumentException(
          "Expected GA JDK " + feature + "; found " + Runtime.version());
    }
    Class<?> data = dataClass(classes);
    Method baselineType = method(data, "baselineType");
    Method baselineIndic = method(data, "baselineIndic");
    Method jdkType = method(Class.forName("jdk.internal.util.regex.Grapheme"), "getType");
    Method[] indic = indicMethods();
    int[] properties = ucd.equals("-") ? null : readIndic(Path.of(ucd));
    int[] typeDeltas = new int[CODE_POINTS];
    int[] indicDeltas = new int[CODE_POINTS];
    for (int cp = 0; cp < CODE_POINTS; cp++) {
      int type = (int) jdkType.invoke(null, cp);
      int fastType = fastType(cp);
      if (fastType >= 0) {
        if (fastType != type) {
          throw new AssertionError("Fast grapheme type mismatch at U+" + Integer.toHexString(cp));
        }
        typeDeltas[cp] = -1;
      } else {
        // Runtime baselineType handles only code points outside the ASCII and pictographic paths.
        typeDeltas[cp] = type == (int) baselineType.invoke(null, cp) ? -1 : type;
        if (cp < 0x378 && typeDeltas[cp] >= 0) {
          throw new AssertionError(
              "Grapheme correction precedes U+0378: U+" + Integer.toHexString(cp));
        }
      }
      int value = properties == null ? indic(indic, cp) : properties[cp];
      if (properties != null && feature >= 22 && value != indic(indic, cp)) {
        throw new AssertionError("Unicode/JDK Indic mismatch at U+" + Integer.toHexString(cp));
      }
      if (cp < 0x915 && (value & 1) != 0) {
        throw new AssertionError("Indic consonant precedes U+0915: U+" + Integer.toHexString(cp));
      }
      indicDeltas[cp] = value == (int) baselineIndic.invoke(null, cp) ? -1 : value;
    }
    Method lookup = data.getDeclaredMethod("lookup", int[].class, int.class);
    lookup.setAccessible(true);
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(output))) {
      for (int[] values : List.of(typeDeltas, indicDeltas)) {
        int[] ranges = ranges(values);
        for (int cp = 0; cp < CODE_POINTS; cp++) {
          if ((int) lookup.invoke(null, ranges, cp) != values[cp]) {
            throw new AssertionError(
                "Compressed property mismatch at U+" + Integer.toHexString(cp));
          }
        }
        out.writeInt(ranges.length);
        for (int value : ranges) {
          out.writeInt(value);
        }
      }
    }
    System.out.println(
        "Extracted "
            + Runtime.version()
            + (properties == null ? "" : " with supplied Unicode Indic data"));
  }

  private static int fastType(int cp) {
    if (cp < 0x7f) {
      return cp >= 0x20 ? 0 : cp == '\r' ? 1 : cp == '\n' ? 2 : 3;
    }
    return Character.isExtendedPictographic(cp) ? 14 : -1;
  }

  private static Method[] indicMethods() throws Exception {
    if (Runtime.version().feature() == 21) {
      return new Method[0];
    }
    Class<?> indic = Class.forName("jdk.internal.util.regex.IndicConjunctBreak");
    return new Method[] {
      method(indic, "isConsonant"), method(indic, "isLinker"), method(indic, "isExtend")
    };
  }

  private static int indic(Method[] methods, int cp) throws Exception {
    int value = 0;
    for (int i = 0; i < methods.length; i++) {
      if ((boolean) methods[i].invoke(null, cp)) {
        value |= 1 << i;
      }
    }
    return value;
  }

  private static int[] readIndic(Path ucd) throws Exception {
    int[] result = new int[CODE_POINTS];
    for (String line : Files.readAllLines(ucd)) {
      String[] fields = line.split("#", 2)[0].split(";");
      if (fields.length != 3 || !fields[1].strip().equals("InCB")) {
        continue;
      }
      int value =
          switch (fields[2].strip()) {
            case "Consonant" -> 1;
            case "Linker" -> 2;
            case "Extend" -> 4;
            default -> throw new IllegalArgumentException("Unknown Indic property: " + line);
          };
      String[] range = fields[0].strip().split("\\.\\.");
      int start = Integer.parseInt(range[0], 16);
      int end = Integer.parseInt(range[range.length - 1], 16);
      Arrays.fill(result, start, end + 1, value);
    }
    if (result[0x0915] != 1 || result[0x094D] != 2 || result[0x200D] != 4) {
      throw new IllegalStateException("Indic data self-check failed");
    }
    return result;
  }

  private static int[] ranges(int[] values) {
    List<Integer> result = new ArrayList<>();
    for (int start = 0; start < values.length; ) {
      int end = start;
      while (end + 1 < values.length && values[end + 1] == values[start]) {
        end++;
      }
      if (values[start] >= 0) {
        result.add(start << 4 | values[start]);
        result.add(end);
      }
      start = end + 1;
    }
    return result.stream().mapToInt(Integer::intValue).toArray();
  }

  private static int[][] read(Path path) throws Exception {
    int[][] result = new int[2][];
    try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
      for (int i = 0; i < result.length; i++) {
        result[i] = new int[in.readInt()];
        for (int j = 0; j < result[i].length; j++) {
          result[i][j] = in.readInt();
        }
      }
    }
    return result;
  }

  private static void append(StringBuilder out, String name, int[] values) {
    out.append("  private static final int[] ").append(name).append(" = {\n");
    for (int i = 0; i < values.length; i += 2) {
      out.append(String.format("    0x%X, 0x%X,\n", values[i], values[i + 1]));
    }
    out.append("  };\n\n");
  }

  private static void check(Path classes) throws Exception {
    Class<?> data = dataClass(classes);
    Method actualType = method(data, "type");
    Method actualIndic = method(data, "indic");
    Method actualConsonant = method(data, "isConsonant");
    Method jdkType = method(Class.forName("jdk.internal.util.regex.Grapheme"), "getType");
    Method[] indic = indicMethods();
    for (int cp = 0; cp < CODE_POINTS; cp++) {
      int expectedIndic = indic(indic, cp);
      if (!actualType.invoke(null, cp).equals(jdkType.invoke(null, cp))
          || (int) actualIndic.invoke(null, cp) != expectedIndic
          || (boolean) actualConsonant.invoke(null, cp) != ((expectedIndic & 1) != 0)) {
        throw new AssertionError("Property mismatch at U+" + Integer.toHexString(cp));
      }
    }
    System.out.println("Verified all " + CODE_POINTS + " code points on " + Runtime.version());
  }
}
