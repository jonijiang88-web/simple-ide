package io.simpleide.hotswap.api;

/**
 * Classification of a class change, used to choose the reload strategy.
 */
public enum ChangeKind {
  /** Only method bodies changed; plain redefineClasses is sufficient. */
  METHOD_BODY,
  /** Fields were added/removed/retyped; versioning is required. */
  SCHEMA,
  /** Method signatures were added/removed/changed; versioning plus bridge handling. */
  SIGNATURE,
  /** Superclass or interface list changed; not safely hot-reloadable. */
  HIERARCHY,
  /** Annotations changed; runtime metadata may need refresh. */
  ANNOTATION,
  /** Unsupported change (native methods, enum constants, etc.). */
  UNSUPPORTED
}
