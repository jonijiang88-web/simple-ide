package io.simpleide.hotswap.api;

/**
 * Invokes a method on the current version object.
 *
 * <p>Owner: facade worktree. Implementations may use MethodHandles/reflection and may cache
 * resolution results, but must support methods added by later versions.
 */
public interface MethodDispatcher {

  /**
   * Invoke one method on the supplied target.
   *
   * @param target current version object (never the facade)
   * @param methodName method name
   * @param descriptor JVM descriptor, for example {@code (Ljava/lang/String;)I}
   * @param args boxed arguments
   * @return boxed return value, or null for void
   * @throws Throwable invocation failure, including the original cause
   */
  Object invoke(Object target, String methodName, String descriptor, Object[] args) throws Throwable;
}
