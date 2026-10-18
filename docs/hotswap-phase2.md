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

## 构建

```bash
./gradlew :hotswap-agent:test
./gradlew test
./gradlew installDist
```
