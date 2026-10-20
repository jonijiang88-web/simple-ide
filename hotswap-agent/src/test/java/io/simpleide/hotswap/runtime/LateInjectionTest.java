package io.simpleide.hotswap.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 单元测试：sidecar 创建之后的框架字段注入必须在业务调用前同步。 */
class LateInjectionTest {
  /** 模拟框架在创建版本对象后才注入依赖，且不覆盖版本对象已有业务状态。 */
  @Test
  void observesDependencyInjectedAfterDelegateCreation() throws Throwable {
    Facade facade = new Facade();
    Version version = new Version();
    facade.__sid_delegate = version;
    facade.dependency = "injected";
    version.count = 7;
    // 验证：业务调用读取到后注入的依赖，同时保留版本内业务字段。
    assertEquals("injected:7", HotswapRuntime.invoke(facade, "read", "()Ljava/lang/String;", new Object[0]));
  }

  /** 原对象尚无依赖时，不清空版本对象在 schema reload 后独立注入的值。 */
  @Test
  void preservesDependencyAlreadyInjectedIntoVersion() throws Throwable {
    Facade facade = new Facade();
    Version version = new Version();
    facade.__sid_delegate = version;
    version.dependency = "version";
    version.count = 9;
    // 验证：facade 空字段不能覆盖版本对象已注入的依赖及业务状态。
    assertEquals("version:9", HotswapRuntime.invoke(facade, "read", "()Ljava/lang/String;", new Object[0]));
  }

  /** Jackson 包装版本对象后，字段级校验应能读取 facade 上的原有字段。 */
  @Test
  void exposesDeserializedFieldsToReflectionValidation() throws Exception {
    Version version = new Version();
    version.dependency = "required";
    Facade facade = (Facade) HotswapRuntime.wrapFacade(Facade.class, version);
    // 验证：框架直接读取 facade 字段时不会误报必填字段为空。
    assertEquals("required", Facade.class.getField("dependency").get(facade));
  }

  /** 模拟保留原始字段供 Spring 注入的 facade。 */
  public static class Facade {
    public Object __sid_delegate;
    @Autowired public String dependency;
    public int count;
  }

  /** 模拟不重新执行构造器的版本对象。 */
  public static class Version {
    @Autowired public String dependency;
    public int count;
    /** 依赖为空时与真实 Mapper 调用一样抛出空指针。 */
    public String read() {
      return dependency.toString() + ":" + count;
    }
  }
}
