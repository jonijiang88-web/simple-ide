package io.simpleide.hotswap.dispatch;

import io.simpleide.hotswap.api.MethodDispatcher;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.objectweb.asm.Type;

/**
 * 基于反射的 {@link MethodDispatcher} 实现，按方法名与 JVM 描述符解析目标方法。
 *
 * <p>解析结果按「目标类 + 方法名 + 描述符」缓存，因此：
 * <ul>
 *   <li>后续版本类新增的方法可以正常解析（未命中的解析结果不缓存）；</li>
 *   <li>不同版本类（不同 Class 对象）互不影响。</li>
 * </ul>
 */
public final class ReflectionMethodDispatcher implements MethodDispatcher {

  private static final Object[] EMPTY_ARGUMENTS = new Object[0];

  /** 按目标类分组的解析缓存；目标类被卸载时缓存自动失效，未命中结果不缓存。 */
  private final ClassValue<ConcurrentMap<String, Method>> cache =
    new ClassValue<ConcurrentMap<String, Method>>() {
      /**
       * 为每个目标类创建独立缓存。
       *
       * @param type 目标类
       * @return 空缓存
       */
      @Override
      protected ConcurrentMap<String, Method> computeValue(Class<?> type) {
        return new ConcurrentHashMap<String, Method>();
      }
    };

  /**
   * 在目标对象上调用一个方法。
   *
   * @param target 当前版本类实例，不能是 facade
   * @param methodName 方法名
   * @param descriptor JVM 描述符，例如 {@code (Ljava/lang/String;)I}
   * @param args 已经装箱的参数
   * @return 装箱后的返回值，void 方法返回 null
   * @throws Throwable 调用失败时抛出原始异常
   */
  @Override
  public Object invoke(Object target, String methodName, String descriptor, Object[] args) throws Throwable {
    if (target == null) {
      throw new NullPointerException("target must not be null");
    }
    if (methodName == null || methodName.isEmpty()) {
      throw new IllegalArgumentException("methodName must not be empty");
    }
    if (descriptor == null || descriptor.isEmpty()) {
      throw new IllegalArgumentException("descriptor must not be empty");
    }
    Object[] actualArguments = args == null ? EMPTY_ARGUMENTS : args;
    Method method = resolve(target.getClass(), methodName, descriptor);
    try {
      return method.invoke(target, actualArguments);
    }
    catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      throw cause == null ? error : cause;
    }
  }

  /**
   * 解析方法，优先使用缓存。
   *
   * @param owner 目标类
   * @param methodName 方法名
   * @param descriptor JVM 描述符
   * @return 反射方法对象
   * @throws NoSuchMethodException 找不到匹配方法时抛出
   */
  private Method resolve(Class<?> owner, String methodName, String descriptor) throws NoSuchMethodException {
    ConcurrentMap<String, Method> ownerCache = cache.get(owner);
    String key = methodName + '\u0000' + descriptor;
    Method cached = ownerCache.get(key);
    if (cached != null) {
      return cached;
    }
    Type[] argumentTypes = Type.getArgumentTypes(descriptor);
    Class<?>[] parameterTypes = new Class<?>[argumentTypes.length];
    for (int index = 0; index < argumentTypes.length; index++) {
      parameterTypes[index] = toClass(argumentTypes[index], owner.getClassLoader());
    }
    Class<?> returnType = toClass(Type.getReturnType(descriptor), owner.getClassLoader());
    Method method = findMethod(owner, methodName, parameterTypes, returnType);
    if (method == null) {
      throw new NoSuchMethodException(owner.getName() + "." + methodName + descriptor);
    }
    makeAccessible(method);
    ownerCache.put(key, method);
    return method;
  }

  /**
   * 沿类层次与接口层次查找方法，返回类型精确匹配的声明优先。
   *
   * @param owner 目标类
   * @param methodName 方法名
   * @param parameterTypes 参数类型
   * @param returnType 返回类型
   * @return 匹配的方法，找不到时返回 null
   */
  private static Method findMethod(Class<?> owner, String methodName, Class<?>[] parameterTypes,
      Class<?> returnType) {
    Method fallback = null;
    for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
      for (Method candidate : current.getDeclaredMethods()) {
        if (!candidate.getName().equals(methodName)) {
          continue;
        }
        if (!Arrays.equals(candidate.getParameterTypes(), parameterTypes)) {
          continue;
        }
        if (candidate.getReturnType().equals(returnType)) {
          return candidate;
        }
        if (fallback == null) {
          fallback = candidate;
        }
      }
      Method fromInterfaces = findInInterfaces(current, methodName, parameterTypes, returnType);
      if (fromInterfaces != null) {
        return fromInterfaces;
      }
    }
    return fallback;
  }

  /**
   * 在类实现或继承的接口中查找方法，用于 default 方法。
   *
   * @param owner 起始类
   * @param methodName 方法名
   * @param parameterTypes 参数类型
   * @param returnType 返回类型
   * @return 匹配的方法，找不到时返回 null
   */
  private static Method findInInterfaces(Class<?> owner, String methodName, Class<?>[] parameterTypes,
      Class<?> returnType) {
    Set<Class<?>> visited = new HashSet<Class<?>>();
    Deque<Class<?>> pending = new ArrayDeque<Class<?>>();
    for (Class<?> candidate : owner.getInterfaces()) {
      pending.add(candidate);
    }
    while (!pending.isEmpty()) {
      Class<?> current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      for (Method candidate : current.getDeclaredMethods()) {
        if (!candidate.getName().equals(methodName)) {
          continue;
        }
        if (!Arrays.equals(candidate.getParameterTypes(), parameterTypes)) {
          continue;
        }
        if (candidate.getReturnType().equals(returnType)) {
          return candidate;
        }
      }
      for (Class<?> parent : current.getInterfaces()) {
        pending.add(parent);
      }
    }
    return null;
  }

  /**
   * 尽力打开方法访问权限；失败时由后续 invoke 抛出明确的访问异常。
   *
   * @param method 反射方法对象
   */
  private static void makeAccessible(Method method) {
    try {
      method.setAccessible(true);
    }
    catch (RuntimeException ignored) {
      // 模块化 JVM 上可能拒绝开放访问，保留原状态让 invoke 报错，不在这里静默成功。
    }
  }

  /**
   * 把 ASM 类型解析为反射类型。
   *
   * @param type ASM 类型
   * @param loader 目标类的类加载器
   * @return 对应 Class 对象
   * @throws NoSuchMethodException 类型无法加载时抛出
   */
  private static Class<?> toClass(Type type, ClassLoader loader) throws NoSuchMethodException {
    switch (type.getSort()) {
      case Type.VOID:
        return void.class;
      case Type.BOOLEAN:
        return boolean.class;
      case Type.BYTE:
        return byte.class;
      case Type.CHAR:
        return char.class;
      case Type.SHORT:
        return short.class;
      case Type.INT:
        return int.class;
      case Type.LONG:
        return long.class;
      case Type.FLOAT:
        return float.class;
      case Type.DOUBLE:
        return double.class;
      case Type.ARRAY:
        Class<?> componentType = toClass(type.getElementType(), loader);
        return Array.newInstance(componentType, 0).getClass();
      case Type.OBJECT:
        try {
          return Class.forName(type.getClassName(), false, loader);
        }
        catch (ClassNotFoundException error) {
          throw new NoSuchMethodException("Cannot resolve parameter type: " + type.getClassName());
        }
      default:
        throw new NoSuchMethodException("Unsupported descriptor type: " + type);
    }
  }
}
