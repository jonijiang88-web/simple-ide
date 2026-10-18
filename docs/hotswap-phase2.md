# Hotswap Phase 2：JRebel 式类版本化

## 目标

在现有 `SIMPLE_IDE_HOTSWAP` 方法体热替换之上，支持：

- 加/删字段
- 加/删方法
- 改方法签名
- 字段状态迁移
- 新版本类定义
- 静态字段共享

不依赖 DevTools。参考 JRebel 的 facade + version class 模型。

## 核心模型

```
Bar（facade，永久保留）
 ├─ __sid_delegate → Bar$$SIDEV1
 ├─ 原字段
 └─ 方法体 → Dispatcher.invoke

Bar$$SIDEV1（新类）
 ├─ 原字段 + 新字段
 ├─ 原方法新实现
 └─ 新方法
```

reload：

1. JPS 编译新 `Bar.class`
2. `VersionClassGenerator` 改名为 `Bar$$SIDEV1`，重写自引用
3. `ClassDefiner` 在同一 ClassLoader/包定义
4. `StateMigrator` 复制字段
5. facade 的 delegate 指向新对象
6. `MethodDispatcher` 调用新版本

## 冻结 API

位于 `hotswap-agent/src/main/java/io/simpleide/hotswap/api/`：

- `ClassInstrumenter`
- `MethodDispatcher`
- `VersionClassGenerator`
- `ClassDefiner`
- `StateMigrator`
- `StaticStateStore`
- `SchemaClassifier`
- `ChangeKind`

**API 文件由主 agent 维护，功能 worktree 不得修改。**

## 并行拆分

| Worktree | 分支 | 文件所有权 | 验收 |
|---|---|---|---|
| facade | `hotswap/facade` | `hotswap-agent/src/main/java/io/simpleide/hotswap/facade/**`, `dispatch/**`, 对应 test | v1 类加载后被改写成 facade；方法调用走 dispatcher |
| version | `hotswap/version` | `.../version/**`, 对应 test | 改名 + 自引用重写 + 同包 defineClass |
| state | `hotswap/state` | `.../state/**`, 对应 test | 字段复制、新字段默认值、静态共享 |
| cli | `hotswap/cli` | `.../classify/**`, `SimpleIde.java`, 对应 test | `reload --class`、schema 分类、只提交目标类 |
| runtime | 主 agent 汇总 | `.../runtime/**`, `HotswapAgent.java` | 接线、错误码、e2e |

## 不支持结构预检

reload 前会检查新 class 的结构，命中以下场景时直接返回明确错误码，不进入版本化：

- 声明内部类、匿名类、nest 成员或包含 `this$0` 字段：`INNER_CLASS_REQUIRES_RESTART`
- 声明 native 方法：`NATIVE_METHOD_REQUIRES_RESTART`

这类 class 在加载期不做 facade 改写，避免加载后立即出现类型或构造器不匹配。普通类的方法体变化仍走 `retransform` / `redefineClasses`。

## 约束

- Java 8 兼容。
- 不改 `api/**`。
- 只改自己 worktree 的文件；提交到自己的分支。
- 每个 worktree 跑 `./gradlew test`。
- 不做 Spring 集成（Phase 3）。
- 不支持的场景必须返回明确错误，不静默成功。

## 验收场景

1. v1 `Bar { int x; String hello() }`
2. v2 `Bar { int x; int y; String hello(); int extra() }`
3. 旧 `Bar` 对象：
   - `hello()` 返回 v2 结果
   - `x` 保留
   - `y` 默认 0
   - 通过 runtime API 可调用 `extra()`
4. 静态字段：v1 修改后 reload，v2 仍读到同一值。

## Phase 1 插件基础设施

`hotswap-agent` 新增 `io.simpleide.hotswap.plugin`：

- `HotswapPlugin`：reload 事件回调，支持 `initialize` 注册 transformer
- `HotswapReloadEvent`：类名、facade、当前版本类、`ChangeKind`
- `PluginManager`：ServiceLoader 加载、批量事件、单个插件异常隔离
- `HotswapRuntime.effectiveClass(...)` / `currentVersionOrSelf(...)` / `wrapFacade(...)`

`HotswapAgent` 按一次 request 批量提交，批次结束后统一分发插件事件，避免 MVC 等框架看到半更新状态。

## Phase 2 Spring 插件

固定运行环境：Spring Boot 2.7.18 / Spring Framework 5.3.31。

`io.simpleide.hotswap.integration` 下的独立插件：

- `spring.SpringBootPlugin`：捕获 ApplicationContext，清理 Spring 注解/内省缓存，补注入版本对象字段
- `spring.SpringMvcPlugin`：重建 Controller 的 `RequestMappingHandlerMapping`，清理异常处理缓存
- `spring.tx.TransactionPlugin`：清理事务属性源和事务 advisor 缓存
- `spring.cache.CachePlugin`：清理缓存注解元数据缓存
- `jackson.JacksonPlugin`：清理 ObjectMapper 序列化器/反序列化器/TypeFactory 缓存
- `hotswap-jackson-adapter`：运行在应用类加载器的 Jackson 2.13.5 effective class 适配器，
  处理新增 DTO 字段的序列化和反序列化
- `mybatis.MyBatisPlugin`：重解析 mapper 注解/XML，清理 MappedStatement、MapperProxy 和反射缓存

公共桥接：

- `SpringContextTransformer`：在 `SpringApplication.run(...)` 返回路径捕获 `ApplicationContext`
- `SpringContextBridge`：agent 与 Spring 类之间的无 Spring 编译依赖桥

支持范围：

- Spring Boot 2.7 / Spring 5.3
- 已有 Controller 方法体变化
- 新增或修改 `@RequestMapping` 方法（含字段与方法同时变化）
- 已有事务/缓存方法的元数据刷新
- Jackson ObjectMapper 序列化/反序列化缓存刷新
- MyBatis mapper 注解重解析与 MappedStatement 刷新

暂不支持：

- `@ControllerAdvice` 的结构性变化
- `@Configuration` / `@Bean` 增删
- 新增事务/缓存方法时重建 Spring 代理
- Jackson facade 反射不可见的新 DTO 字段
- MyBatis XML 中无法解析 namespace 或没有对应 mapper class 的资源变化

## 固定版本

插件按以下版本实现和验证：

| 组件 | 版本 |
|---|---|
| Spring Boot | 2.7.18 |
| Spring Framework | 5.3.31 |
| Jackson | 2.13.5 |
| MyBatis | 3.5.13 |
| mybatis-spring-boot-starter | 2.3.1 |

插件仍通过反射访问框架 API；固定版本保证内部字段/方法名有明确依据。

### 插件能力矩阵

| 插件 | 方法体 | 注解/映射 | 字段结构 | 说明 |
|---|---|---|---|---|
| SpringBootPlugin | 缓存刷新 | - | 版本对象补注入 | 捕获 ApplicationContext |
| SpringMvcPlugin | - | 重建 RequestMapping | - | 新增/修改 URL |
| TransactionPlugin | 事务执行 | 清属性缓存 | - | 代理重建不在范围 |
| CachePlugin | 缓存执行 | 版本感知 CacheOperationSource | - | 不清业务缓存数据 |
| JacksonPlugin | 序列化执行 | 清 Jackson 缓存 | 新 DTO 字段由 adapter 适配 | 清 ObjectMapper 缓存 |
| MyBatisPlugin | SQL 执行 | 重解析 mapper 注解/XML | - | mapper XML 变化自动触发对应类 reload |

## 编译期调用点改写

开启 `SIMPLE_IDE_HOTSWAP` 后，simple-ide 在 JPS 编译成功后会对项目输出 class 做一次编译后处理：

- 扫描项目输出中的 class 集合
- 把应用类之间的实例方法调用（`invokevirtual` / `invokeinterface`）改成生成的静态桥
- 桥方法通过 `HotswapRuntime.invoke` 解析当前版本类
- 为每次调用签名生成唯一桥类，避免已加载桥类无法新增方法

这样即使 `UserService` 是 Spring 自动注入的 facade Bean，Controller 中重新编译的：

```java
userService.newMethod()
```

也可以调用版本类新增的方法。

当前支持：

- 重新编译的 Controller / Service 直接调用已有类新增方法
- Spring CGLIB 代理：已有方法仍走代理，代理方法表没有的新方法回退到 target
- 新 class 首次加载后使用改写后的调用点

暂不支持：

- 未重新编译的第三方 jar 调用点
- 直接字段访问 `getfield` / `putfield`
- 构造器、静态方法调用
- 代理新增方法上的事务/缓存拦截语义

### Jackson effective class adapter

`hotswap-jackson-adapter` 是独立的可选 jar，由 `simple-ide run` 在 hotswap 模式加入应用 Classpath。
它通过 Jackson 2.13.5 的 `BeanSerializerModifier` / `BeanDeserializerModifier`：

- 序列化 facade DTO 时切换到当前版本对象
- 反序列化时按版本类读取字段，再包装回 facade
- 插件由应用类加载器首次 reload 时通过 `ServiceLoader` 加载

因此新增 DTO 字段可以同时支持：

```json
{"name":"x","age":20,"email":"x@y"}
```

以及 `@RequestBody` 反向绑定。

## build 自动 reload

设置 `SIMPLE_IDE_HOTSWAP=1` 且存在同环境启动的运行中应用时，`simple-ide build` 会：

1. 使用 JPS 增量编译到共享 `target/classes`（不停止应用）
2. 从构建输出中筛选变更 class
3. 自动提交给 agent，完成一次 reload
4. 跳过原有的“停止应用 → 编译 → 重启应用”流程

可用 `--configuration <name>` 限定运行配置。构建 JSON 增加 `hotswapReload`（单配置）或 `hotswapReloads`（多配置）。

## 构建

```bash
./gradlew :hotswap-agent:test
./gradlew test
./gradlew installDist
```
