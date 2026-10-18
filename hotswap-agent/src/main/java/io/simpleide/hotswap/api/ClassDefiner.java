package io.simpleide.hotswap.api;

/**
 * Defines a version class in the same runtime package and class loader as the original.
 *
 * <p>Owner: version worktree. Implementations may use Unsafe.defineClass (Java 8) or
 * MethodHandles.Lookup.defineClass (Java 9+). A different class loader is not acceptable.
 */
public interface ClassDefiner {

  /**
   * Define one version class.
   *
   * @param original original facade class
   * @param versionName dotted version class name
   * @param versionBytes version class bytes
   * @return defined class
   * @throws Exception when definition fails
   */
  Class<?> define(Class<?> original, String versionName, byte[] versionBytes) throws Exception;
}
