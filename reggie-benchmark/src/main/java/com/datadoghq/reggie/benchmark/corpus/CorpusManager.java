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
package com.datadoghq.reggie.benchmark.corpus;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;

/**
 * Manages large corpus files for benchmarking. Downloads corpora on demand with fallback to bundled
 * resources.
 */
public class CorpusManager {

  private static final Path CORPUS_DIR = Path.of("downloads/corpus-large");
  private static final String FALLBACK_RESOURCE_PATH = "/corpus/";

  public enum Corpus {
    GUTENBERG_SMALL("gutenberg_small"),
    ACCESS_LOG("access_log");

    private final String name;

    Corpus(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }

  /**
   * Ensure corpus is available. Tries download first, falls back to bundled resources if download
   * fails.
   */
  public Path ensureCorpus(Corpus corpus) throws IOException {
    if (!Files.exists(CORPUS_DIR)) {
      Files.createDirectories(CORPUS_DIR);
    }

    Path corpusFile = CORPUS_DIR.resolve(corpus.getName() + ".txt");

    // If already downloaded, return it
    if (Files.exists(corpusFile)) {
      System.out.println("[Corpus] " + corpus.getName() + " already exists at: " + corpusFile);
      return corpusFile;
    }

    // Try to download
    CorpusEntry config = getCorpusConfig(corpus);
    if (config != null && config.url != null && !config.url.startsWith("placeholder")) {
      try {
        System.out.println("[Corpus] Downloading " + corpus.getName() + " from " + config.url);
        downloadCorpus(config.url, corpusFile);

        // Verify checksum if available
        if (config.sha256 != null && !config.sha256.startsWith("placeholder")) {
          if (!verifyChecksum(corpusFile, config.sha256)) {
            System.err.println("[Corpus] Checksum mismatch for " + corpus.getName() + ", deleting");
            Files.deleteIfExists(corpusFile);
          } else {
            System.out.println("[Corpus] Download complete, checksum verified");
            return corpusFile;
          }
        } else {
          System.out.println("[Corpus] Download complete (no checksum verification)");
          return corpusFile;
        }
      } catch (IOException e) {
        System.err.println("[Corpus] Download failed: " + e.getMessage());
        Files.deleteIfExists(corpusFile);
      }
    }

    // Fallback to bundled resources
    System.out.println("[Corpus] Using bundled fallback for " + corpus.getName());
    copyFallbackFromResources(corpus, corpusFile);
    return corpusFile;
  }

  /** Download corpus from URL to target path. */
  private void downloadCorpus(String url, Path target) throws IOException {
    Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");

    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpGet request = new HttpGet(url);

      httpClient.execute(
          request,
          (ClassicHttpResponse response) -> {
            int status = response.getCode();
            if (status < 200 || status >= 300) {
              throw new IOException("HTTP error: " + status);
            }

            HttpEntity entity = response.getEntity();
            if (entity == null) {
              throw new IOException("Empty response");
            }

            try (InputStream in = entity.getContent();
                OutputStream out = Files.newOutputStream(tempFile)) {
              byte[] buffer = new byte[8192];
              int bytesRead;
              long total = 0;
              while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                total += bytesRead;
                if (total % (1024 * 1024) == 0) {
                  System.out.println("[Corpus] Downloaded " + (total / 1024 / 1024) + " MB...");
                }
              }
            }
            return null;
          });

      // Move temp file to final location
      Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  /** Verify SHA256 checksum of a file. */
  private boolean verifyChecksum(Path file, String expectedSha256) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
          digest.update(buffer, 0, bytesRead);
        }
      }
      String actualSha256 = HexFormat.of().formatHex(digest.digest());
      return actualSha256.equalsIgnoreCase(expectedSha256);
    } catch (NoSuchAlgorithmException | IOException e) {
      System.err.println("[Corpus] Checksum verification failed: " + e.getMessage());
      return false;
    }
  }

  /** Copy fallback corpus from bundled resources. */
  private void copyFallbackFromResources(Corpus corpus, Path target) throws IOException {
    String resourcePath = FALLBACK_RESOURCE_PATH + corpus.getName() + ".txt";
    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IOException("Fallback resource not found: " + resourcePath);
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      System.out.println("[Corpus] Copied fallback resource to: " + target);
    }
  }

  /** Get configuration for a specific corpus from corpus-config.json. */
  private CorpusEntry getCorpusConfig(Corpus corpus) {
    try {
      CorpusConfig config = loadConfig();
      if (config != null && config.corpora != null) {
        for (CorpusEntry entry : config.corpora) {
          if (corpus.getName().equals(entry.name)) {
            return entry;
          }
        }
      }
    } catch (IOException e) {
      System.err.println("[Corpus] Failed to load config: " + e.getMessage());
    }
    return null;
  }

  /** Stream lines from corpus file efficiently. */
  public Stream<String> streamLines(Corpus corpus, int limit) throws IOException {
    Path file = ensureCorpus(corpus);
    return Files.lines(file, StandardCharsets.UTF_8).limit(limit);
  }

  /** Load corpus configuration from JSON. */
  private CorpusConfig loadConfig() throws IOException {
    try (InputStream is = getClass().getResourceAsStream("/corpus-config.json")) {
      if (is == null) {
        throw new IOException("corpus-config.json not found");
      }
      Gson gson = new Gson();
      return gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), CorpusConfig.class);
    }
  }

  /** Command-line interface for managing corpora. */
  public static void main(String[] args) throws IOException {
    CorpusManager manager = new CorpusManager();

    if (args.length > 0 && args[0].equals("download")) {
      System.out.println("=== Downloading Test Corpora ===\n");

      for (Corpus corpus : Corpus.values()) {
        System.out.println("Processing corpus: " + corpus.getName());
        Path file = manager.ensureCorpus(corpus);
        System.out.println("Corpus available at: " + file + " (" + Files.size(file) + " bytes)");
        System.out.println();
      }

      System.out.println("=== Download Complete ===");
    } else {
      System.out.println("Usage: CorpusManager download");
      System.out.println("  download - Download all configured corpora");
    }
  }

  // Configuration data model
  private static class CorpusConfig {
    List<CorpusEntry> corpora;
  }

  private static class CorpusEntry {
    String name;
    String description;
    String url;
    long size;
    String sha256;
  }
}
