package io.simpleide.hotswap.api;

/**
 * Shared storage for static fields across class versions.
 *
 * <p>Owner: state worktree. The instrumenter rewrites static field access to call this
 * store, so every version reads/writes the same value.
 */
public interface StaticStateStore {

  /**
   * Read a static field value.
   *
   * @param className dotted class name that originally declared the field
   * @param fieldName field name
   * @return current value
   */
  Object get(String className, String fieldName);

  /**
   * Write a static field value.
   *
   * @param className dotted class name that originally declared the field
   * @param fieldName field name
   * @param value new value
   */
  void set(String className, String fieldName, Object value);

  /**
   * Capture initial static values from a loaded class before the first version is created.
   *
   * @param type original class
   * @throws Exception when a static field cannot be read
   */
  void capture(Class<?> type) throws Exception;
}
