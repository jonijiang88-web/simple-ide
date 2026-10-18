package io.simpleide.hotswap.facade;

import io.simpleide.hotswap.api.OriginalClassStore;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试用原始字节存储：记录每次写入，便于断言。
 */
final class RecordingOriginalClassStore implements OriginalClassStore {

  private final Map<String, byte[]> recorded = new LinkedHashMap<String, byte[]>();

  /**
   * 记录原始字节。
   *
   * @param className 点分类型名
   * @param originalBytes 原始类字节
   */
  @Override
  public void record(String className, byte[] originalBytes) {
    recorded.put(className, originalBytes);
  }

  /**
   * 读取某个类记录的原始字节。
   *
   * @param className 点分类型名
   * @return 原始字节，未记录时返回 null
   */
  byte[] bytesOf(String className) {
    return recorded.get(className);
  }

  /**
   * 返回已记录的类型名集合。
   *
   * @return 记录的类型名
   */
  java.util.Set<String> recordedNames() {
    return recorded.keySet();
  }
}
