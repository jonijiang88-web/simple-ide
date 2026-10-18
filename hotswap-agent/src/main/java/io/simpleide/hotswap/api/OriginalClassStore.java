package io.simpleide.hotswap.api;

/**
 * Stores the original (pre-facade) bytes of each instrumented class so that version 0 can
 * be produced on demand.
 */
public interface OriginalClassStore {

  /**
   * Record original bytes.
   *
   * @param className dotted class name
   * @param originalBytes bytes before facade instrumentation
   */
  void record(String className, byte[] originalBytes);
}
