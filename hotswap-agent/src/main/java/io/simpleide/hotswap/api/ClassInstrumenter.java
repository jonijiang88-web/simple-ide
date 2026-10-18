package io.simpleide.hotswap.api;

/**
 * Rewrites an application class so that instance methods dispatch through the
 * simple-ide runtime instead of running the original bytecode in the facade.
 *
 * <p>Owner: facade worktree. Implementations must not touch runtime/state/version code.
 */
public interface ClassInstrumenter {

  /**
   * Instrument one class before it is defined by the application class loader.
   *
   * @param originalBytes bytes exactly as compiled by JPS
   * @param className dotted class name
   * @param loader application class loader
   * @return facade bytes; must be a valid class with the same name/superclass/interfaces
   * @throws Exception when the class cannot be instrumented
   */
  byte[] instrument(byte[] originalBytes, String className, ClassLoader loader) throws Exception;
}
