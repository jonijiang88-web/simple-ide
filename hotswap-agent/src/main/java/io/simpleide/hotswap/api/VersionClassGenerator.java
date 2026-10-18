package io.simpleide.hotswap.api;

/**
 * Produces a new class identity for a reloaded class.
 *
 * <p>Owner: version worktree. The generator must rename the class and rewrite every
 * self-reference (descriptors, signatures, this type, constructor name) from
 * {@code originalName} to {@code versionName}. References to other classes stay unchanged.
 */
public interface VersionClassGenerator {

  /**
   * Generate one version class.
   *
   * @param originalBytes newly compiled bytes of the original class
   * @param originalName dotted original class name
   * @param versionName dotted version class name, for example {@code com.demo.Bar$$SIDEV1}
   * @return version class bytes
   * @throws Exception when the class cannot be remapped
   */
  byte[] generate(byte[] originalBytes, String originalName, String versionName) throws Exception;
}
