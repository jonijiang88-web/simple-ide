package io.simpleide.hotswap.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * 编译期调用点改写的 invokedynamic bootstrap。
 *
 * <p>调用点的原始描述符保持不变，bootstrap 将其适配为
 * {@link HotswapRuntime#invoke(Object, String, String, Object[])} 调用；
 * 运行时由版本类解析方法，不依赖生成桥类的方法表。
 */
public final class HotswapBootstrap {

  private HotswapBootstrap() {
  }

  /**
   * 创建调用点。
   *
   * @param lookup 调用者 lookup
   * @param invokedName 原方法名
   * @param invokedType 调用点类型（receiver + 参数 + 返回类型）
   * @param owner 原 owner 内部名
   * @param originalName 原方法名
   * @param originalDescriptor 原方法描述符（不含 receiver）
   * @return 调用点
   * @throws Throwable 方法句柄适配失败
   */
  public static CallSite bootstrap(MethodHandles.Lookup lookup, String invokedName,
                                   MethodType invokedType, String owner, String originalName,
                                   String originalDescriptor) throws Throwable {
    MethodHandle target = lookup.findStatic(HotswapRuntime.class, "invoke",
      MethodType.methodType(Object.class, Object.class, String.class, String.class,
        Object[].class));
    target = MethodHandles.insertArguments(target, 1, originalName);
    target = MethodHandles.insertArguments(target, 1, originalDescriptor);
    int argumentCount = invokedType.parameterCount() - 1;
    target = target.asCollector(Object[].class, argumentCount);
    target = target.asType(invokedType);
    return new ConstantCallSite(target);
  }
}
