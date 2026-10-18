# spring-boot-devtools-2.7.18-simpleide.jar

基于上游 `org.springframework.boot:spring-boot-devtools:2.7.18`（Apache-2.0）。

## 补丁

`RestartClassLoader` 默认对以下 3 个纯 Spring 覆盖类优先委托父加载器（可用系统属性覆盖）：

```
-Dspring.devtools.restart.parent-first-classes=org.springframework.security.authentication.UsernamePasswordAuthenticationToken,org.springframework.security.core.authority.SimpleGrantedAuthority,org.springframework.aop.aspectj.AbstractAspectJAdvice
```

原因：应用输出目录（`target/classes`）可能覆盖依赖 jar 中同名类（例如 Artemis 在 `src/main/java/org/springframework/...` 下覆盖 Spring Security）。DevTools 默认 parent-last 会让父子加载器各持一份同名类，触发 `LinkageError: loader constraint violation`。parent-first 后这些纯框架类统一由父加载器加载，应用类仍然由 `RestartClassLoader` 重启。

注意：不要简单使用 `org.springframework.` 全包前缀。Artemis 还覆盖了 `DefaultWebResponseExceptionTranslator`，它引用应用类（`ApplicationContextUtils`）；把它放到父加载器会加载另一份应用类，导致静态上下文为 null。只对不引用应用类的纯 Spring 覆盖类做 parent-first。

覆盖类如果引用应用代码，需要把其依赖 jar 放入 restart classpath，例如：

```
simple-ide run ... --hot-reload \
  --hot-reload-include 'spring-security-oauth2-2\.5\.2\.RELEASE\.jar'
```

## Artemis 实测限制

- 启动：上述 patched jar + `--hot-reload-include` 可成功启动，健康检查 `UP`。
- reload：编译和 trigger 均成功，新代码日志已出现；但全量 context 重启仍失败：
  - `-XX:MaxMetaspaceSize=1024m` 时 `OutOfMemoryError: Metaspace`
  - 提高到 2048m 后继续到 `HystrixConfig`，报 `IllegalStateException: Another strategy was already registered`（Hystrix/Archaius 静态状态未随 context 重启重置）
- 结论：Artemis 这类大量静态初始化 + 覆盖框架类的工程，不适合 DevTools 全量 context 重启；应使用普通 run/重启，或 DCEVM/JRebel 这类原地热重载方案。

完整 diff 见 `spring-boot-devtools-2.7.18-simpleide.patch`。

## 重新构建

```bash
# 1. 解压上游 sources jar，修改 RestartClassLoader.java
javac -source 8 -target 8 \
  -classpath "spring-boot-devtools-2.7.18.jar:spring-core-5.3.31.jar:spring-jcl-5.3.31.jar" \
  -d patch-classes RestartClassLoader.java

# 2. 用编译产物替换原 jar 中的 class
cp spring-boot-devtools-2.7.18.jar spring-boot-devtools-2.7.18-simpleide.jar
jar uf spring-boot-devtools-2.7.18-simpleide.jar \
  -C patch-classes org/springframework/boot/devtools/restart/classloader/RestartClassLoader.class
```
