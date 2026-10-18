package io.simpleide.hotswap;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
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
 * <p>Only method-body changes are supported by the JVM's redefinition API. Schema
 * changes fail with a clear error and must fall back to a restart.
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
    try {
      Files.createDirectories(directory.resolve("requests"));
      Files.createDirectories(directory.resolve("classes"));
      Files.createDirectories(directory.resolve("results"));
      log(directory, "agent started: " + directory.toAbsolutePath());
    }
    catch (IOException error) {
      throw new IllegalStateException("Cannot initialize hotswap directory: " + directory, error);
    }
    Thread worker = new Thread(new Worker(directory, instrumentation), "simple-ide-hotswap");
    worker.setDaemon(true);
    worker.start();
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
      List<ClassDefinition> definitions = new ArrayList<ClassDefinition>();
      List<String> skipped = new ArrayList<String>();
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
          definitions.add(new ClassDefinition(target, Files.readAllBytes(classFile)));
        }
        if (definitions.isEmpty()) {
          writeResult(id, "OK\n" + "redefined=0\n" + "skipped=" + skipped.size() + "\n");
          log("request " + id + ": no loaded classes to redefine, skipped=" + skipped);
        }
        else {
          instrumentation.redefineClasses(definitions.toArray(new ClassDefinition[0]));
          writeResult(id, "OK\n" + "redefined=" + definitions.size() + "\n"
            + "skipped=" + skipped.size() + "\n");
          log("request " + id + ": redefined=" + definitions.size() + ", skipped=" + skipped);
        }
      }
      catch (UnmodifiableClassException error) {
        writeResult(id, "ERROR\nUNMODIFIABLE\n" + error.getMessage() + "\n");
        log("request " + id + ": UNMODIFIABLE " + error);
      }
      catch (UnsupportedOperationException error) {
        writeResult(id, "ERROR\nSCHEMA_CHANGE_REQUIRES_RESTART\n" + error.getMessage() + "\n");
        log("request " + id + ": SCHEMA_CHANGE_REQUIRES_RESTART " + error);
      }
      catch (Throwable error) {
        writeResult(id, "ERROR\nREDEFINE_FAILED\n" + error + "\n");
        log("request " + id + ": REDEFINE_FAILED " + error);
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
