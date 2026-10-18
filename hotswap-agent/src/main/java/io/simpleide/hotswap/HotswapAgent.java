package io.simpleide.hotswap;

import io.simpleide.hotswap.runtime.HotswapPreflight;
import io.simpleide.hotswap.runtime.HotswapRuntime;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * simple-ide hotswap agent.
 *
 * <p>Protocol (all paths relative to the agent directory):
 * <pre>
 * requests/&lt;id&gt;.req   one class path per line, e.g. com/demo/Bar.class
 * classes/&lt;path&gt;      replacement class bytes
 * results/&lt;id&gt;.txt    OK / ERROR:code / message
 * </pre>
 *
 * <p>Facade classes are versioned by {@link HotswapRuntime}; classes that were not
 * instrumented fall back to the JVM's redefineClasses API.
 */
public final class HotswapAgent {

  private static final long POLL_MILLIS = 200L;

  private HotswapAgent() {
  }

  /** JVM -javaagent entry point. */
  public static void premain(String agentArgs, Instrumentation instrumentation) {
    start(agentArgs, instrumentation);
  }

  /** Attach entry point (reserved for future use). */
  public static void agentmain(String agentArgs, Instrumentation instrumentation) {
    start(agentArgs, instrumentation);
  }

  private static void start(String agentArgs, Instrumentation instrumentation) {
    Path directory = parseDirectory(agentArgs);
    String include = parseOption(agentArgs, "include", "");
    try {
      Files.createDirectories(directory.resolve("requests"));
      Files.createDirectories(directory.resolve("classes"));
      Files.createDirectories(directory.resolve("results"));
      log(directory, "agent started: " + directory.toAbsolutePath() + ", include=" + include);
    }
    catch (IOException error) {
      throw new IllegalStateException("Cannot initialize hotswap directory: " + directory, error);
    }
    HotswapRuntime.getInstance().configure(instrumentation, directory, include);
    Thread worker = new Thread(new Worker(directory, instrumentation), "simple-ide-hotswap");
    worker.setDaemon(true);
    worker.start();
  }

  /**
   * 解析 agent 参数。
   *
   * @param agentArgs agent 参数
   * @param key 参数键
   * @param fallback 默认值
   * @return 参数值
   */
  private static String parseOption(String agentArgs, String key, String fallback) {
    String value = agentArgs == null ? "" : agentArgs.trim();
    String prefix = key + "=";
    for (String part : value.split(",")) {
      String entry = part.trim();
      if (entry.startsWith(prefix)) {
        return entry.substring(prefix.length());
      }
    }
    return fallback;
  }

  private static Path parseDirectory(String agentArgs) {
    String value = agentArgs == null ? "" : agentArgs.trim();
    for (String part : value.split(",")) {
      String entry = part.trim();
      if (entry.startsWith("dir=")) {
        return Paths.get(entry.substring(4)).toAbsolutePath().normalize();
      }
    }
    throw new IllegalArgumentException("hotswap agent requires dir=<path>");
  }

  private static final class Worker implements Runnable {

    private final Path directory;
    private final Instrumentation instrumentation;

    private Worker(Path directory, Instrumentation instrumentation) {
      this.directory = directory;
      this.instrumentation = instrumentation;
    }

    @Override
    public void run() {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          processRequests();
          Thread.sleep(POLL_MILLIS);
        }
        catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
        catch (Throwable error) {
          logQuietly("worker error: " + error);
        }
      }
    }

    private void processRequests() throws IOException {
      Path requests = directory.resolve("requests");
      List<Path> files = new ArrayList<Path>();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(requests, "*.req")) {
        for (Path file : stream) {
          files.add(file);
        }
      }
      Collections.sort(files, new Comparator<Path>() {
        @Override
        public int compare(Path left, Path right) {
          return left.getFileName().toString().compareTo(right.getFileName().toString());
        }
      });
      for (Path request : files) {
        process(request);
      }
    }

    private void process(Path request) throws IOException {
      String id = request.getFileName().toString();
      id = id.substring(0, id.length() - ".req".length());
      List<String> classPaths = Files.readAllLines(request, StandardCharsets.UTF_8);
      List<String> skipped = new ArrayList<String>();
      int reloaded = 0;
      try {
        for (String line : classPaths) {
          String relative = line.trim();
          if (relative.isEmpty()) {
            continue;
          }
          Path classFile = directory.resolve("classes").resolve(relative);
          if (!Files.isRegularFile(classFile)) {
            throw new IllegalStateException("Missing class bytes: " + relative);
          }
          String className = relative.substring(0, relative.length() - ".class".length())
            .replace('/', '.').replace('\\', '.');
          Class<?> target = findLoadedClass(className);
          if (target == null) {
            skipped.add(className);
            continue;
          }
          HotswapRuntime.getInstance().reload(className, Files.readAllBytes(classFile));
          reloaded++;
        }
        writeResult(id, "OK\n" + "reloaded=" + reloaded + "\n"
          + "skipped=" + skipped.size() + "\n");
        log("request " + id + ": reloaded=" + reloaded + ", skipped=" + skipped);
      }
      catch (UnsupportedOperationException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        String preflight = HotswapPreflight.codeOf(message);
        String code = preflight == null ? "SCHEMA_CHANGE_REQUIRES_RESTART" : preflight;
        writeResult(id, "ERROR\n" + code + "\n" + message + "\n");
        log("request " + id + ": " + code + " " + error);
      }
      catch (Throwable error) {
        writeResult(id, "ERROR\nHOTSWAP_FAILED\n" + error + "\n");
        log("request " + id + ": HOTSWAP_FAILED " + error);
      }
      finally {
        Files.deleteIfExists(request);
      }
    }

    private Class<?> findLoadedClass(String name) {
      Class<?> candidate = null;
      for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
        if (!loaded.getName().equals(name)) {
          continue;
        }
        if (loaded.getClassLoader() == null) {
          continue;
        }
        if (loaded.getClassLoader() == ClassLoader.getSystemClassLoader()) {
          return loaded;
        }
        if (candidate == null) {
          candidate = loaded;
        }
      }
      return candidate;
    }

    private void writeResult(String id, String content) throws IOException {
      Path temporary = directory.resolve("results").resolve(id + ".tmp");
      Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
      Files.move(temporary, directory.resolve("results").resolve(id + ".txt"),
        StandardCopyOption.REPLACE_EXISTING);
    }

    private void log(String message) {
      HotswapAgent.log(directory, message);
    }

    private void logQuietly(String message) {
      try {
        log(message);
      }
      catch (Throwable ignored) {
      }
    }
  }

  private static void log(Path directory, String message) {
    try {
      String line = LocalDateTime.now() + " " + message + System.lineSeparator();
      Files.write(directory.resolve("agent.log"), line.getBytes(StandardCharsets.UTF_8),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
    catch (IOException ignored) {
    }
  }
}
