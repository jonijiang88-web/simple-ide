package io.simpleide.hotswap.api;

/**
 * Copies instance state from one class version to another.
 *
 * <p>Owner: state worktree. Fields are matched by name and type while walking the class
 * hierarchy. New fields keep JVM defaults. Static fields are handled separately.
 */
public interface StateMigrator {

  /**
   * Copy instance fields from source to target.
   *
   * @param from old version object
   * @param to new version object
   * @throws Exception when a matching field cannot be copied
   */
  void copy(Object from, Object to) throws Exception;
}
