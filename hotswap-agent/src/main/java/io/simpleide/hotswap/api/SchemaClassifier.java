package io.simpleide.hotswap.api;

/**
 * Compares old and new class bytes and classifies the change.
 *
 * <p>Owner: cli/classify worktree. The result must be conservative: when unsure, return
 * HIERARCHY or UNSUPPORTED rather than a reloadable kind.
 */
public interface SchemaClassifier {

  /**
   * Classify a change.
   *
   * @param oldBytes currently loaded class bytes
   * @param newBytes newly compiled class bytes
   * @return conservative change kind
   * @throws Exception when either input cannot be parsed
   */
  ChangeKind classify(byte[] oldBytes, byte[] newBytes) throws Exception;
}
