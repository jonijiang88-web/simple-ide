package io.simpleide;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maven 多模块模型解析与目标模块选择。
 *
 * <p>解决「run/test 固定使用根目录 target/classes 与第一个 classpath 缓存」造成的多模块错配：
 * 依据 pom.xml 的模块拓扑、自定义输出目录与模块间依赖，选出精确的编译输出与依赖来源；
 * 无法唯一确定时显式报错，而不是静默挑一个。
 */
final class ModuleResolver {

  /** 单模块信息。 */
  static final class Module {
    /** 模块名（artifactId）。 */
    final String name;
    /** 模块根目录。 */
    final Path baseDir;
    /** 主编译输出目录。 */
    final Path outputDir;
    /** 测试编译输出目录。 */
    final Path testOutputDir;
    /** 主源码目录。 */
    final Path sourceDir;
    /** 测试源码目录。 */
    final Path testSourceDir;
    /** 依赖的模块名（artifactId）。 */
    final List<String> dependencyNames = new ArrayList<String>();

    Module(String name, Path baseDir, Path outputDir, Path testOutputDir, Path sourceDir, Path testSourceDir) {
      this.name = name;
      this.baseDir = baseDir;
      this.outputDir = outputDir;
      this.testOutputDir = testOutputDir;
      this.sourceDir = sourceDir;
      this.testSourceDir = testSourceDir;
    }

    /** 是否包含主源码文件。 */
    boolean hasMainSources() {
      return containsJavaFile(sourceDir);
    }

    /** 是否包含测试源码文件。 */
    boolean hasTestSources() {
      return containsJavaFile(testSourceDir);
    }
  }

  /** 禁止实例化。 */
  private ModuleResolver() {
  }

  /**
   * 解析项目的 Maven 模块拓扑（根模块在前）。
   *
   * @param project 项目根目录
   * @return 模块列表；无 pom.xml 时返回空列表
   * @throws IOException 解析异常
   */
  static List<Module> resolve(Path project) throws IOException {
    List<Module> modules = new ArrayList<Module>();
    Path rootPom = project.resolve("pom.xml");
    if (!Files.isRegularFile(rootPom)) return modules;
    resolveRecursive(rootPom, modules);
    return modules;
  }

  /** 递归解析 pom 及其子模块。 */
  private static void resolveRecursive(Path pom, List<Module> modules) throws IOException {
    if (!Files.isRegularFile(pom)) return;
    Element root;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      Document document = factory.newDocumentBuilder().parse(pom.toFile());
      root = document.getDocumentElement();
    }
    catch (Exception e) {
      throw new IOException("解析 pom.xml 失败: " + pom + " (" + e.getMessage() + ")", e);
    }

    Path baseDir = pom.getParent();
    String artifactId = childText(root, "artifactId");
    if (artifactId == null || artifactId.trim().isEmpty()) {
      artifactId = baseDir.getFileName() != null ? baseDir.getFileName().toString() : "unnamed";
    }

    Path sourceDir = baseDir.resolve("src/main/java");
    Path testSourceDir = baseDir.resolve("src/test/java");
    Path outputDir = baseDir.resolve("target/classes");
    Path testOutputDir = baseDir.resolve("target/test-classes");

    Element build = childElement(root, "build");
    if (build != null) {
      String out = childText(build, "outputDirectory");
      if (out != null && !out.trim().isEmpty()) outputDir = baseDir.resolve(out.trim()).normalize();
      String testOut = childText(build, "testOutputDirectory");
      if (testOut != null && !testOut.trim().isEmpty()) testOutputDir = baseDir.resolve(testOut.trim()).normalize();
      String src = childText(build, "sourceDirectory");
      if (src != null && !src.trim().isEmpty()) sourceDir = baseDir.resolve(src.trim()).normalize();
      String testSrc = childText(build, "testSourceDirectory");
      if (testSrc != null && !testSrc.trim().isEmpty()) testSourceDir = baseDir.resolve(testSrc.trim()).normalize();
    }

    Module module = new Module(artifactId, baseDir, outputDir, testOutputDir, sourceDir, testSourceDir);
    collectDependencyNames(root, module);
    modules.add(module);

    for (Path subPom : subModulePoms(root, baseDir)) {
      resolveRecursive(subPom, modules);
    }
  }

  /** 收集项目内模块依赖名（含所有 <dependency> 的 artifactId，后续按模块集合过滤）。 */
  private static void collectDependencyNames(Element root, Module module) {
    Element dependencies = childElement(root, "dependencies");
    if (dependencies == null) return;
    for (Element dependency : childElements(dependencies, "dependency")) {
      String artifactId = childText(dependency, "artifactId");
      if (artifactId != null && !artifactId.trim().isEmpty()) {
        module.dependencyNames.add(artifactId.trim());
      }
    }
  }

  /** 返回子模块 pom 路径列表。 */
  private static List<Path> subModulePoms(Element root, Path baseDir) {
    List<Path> poms = new ArrayList<Path>();
    Element modules = childElement(root, "modules");
    if (modules == null) return poms;
    for (Element moduleEl : childElements(modules, "module")) {
      String relative = moduleEl.getTextContent();
      if (relative == null || relative.trim().isEmpty()) continue;
      Path candidate = baseDir.resolve(relative.trim()).normalize();
      Path pom = Files.isDirectory(candidate) ? candidate.resolve("pom.xml") : candidate;
      if (Files.isRegularFile(pom)) poms.add(pom);
    }
    return poms;
  }

  /**
   * 依据 RunConfiguration 的模块名选择目标模块。
   *
   * @param modules 模块列表
   * @param hint IntelliJ 模块名（如 {@code artemis.artemis-web}）或 artifactId；为空表示未指定
   * @return 选中的模块
   * @throws IOException 未指定且多模块、或匹配不到、或匹配到多个时抛出
   */
  static Module select(List<Module> modules, String hint) throws IOException {
    if (modules.isEmpty()) throw new IOException("未解析到任何 Maven 模块（缺少 pom.xml？）");
    if (hint == null || hint.trim().isEmpty()) {
      if (modules.size() == 1) return modules.get(0);
      List<String> names = new ArrayList<String>();
      for (Module module : modules) names.add(module.name);
      throw new IOException("多模块项目必须显式指定目标模块（--module <artifactId> 或 RunConfiguration 的 module），可用模块: " + names);
    }
    String wanted = hint.trim();
    String lastSegment = wanted.contains(".") ? wanted.substring(wanted.lastIndexOf('.') + 1) : wanted;
    List<Module> matches = new ArrayList<Module>();
    for (Module module : modules) {
      if (module.name.equals(wanted) || module.name.equals(lastSegment)
        || module.baseDir.getFileName().toString().equals(wanted)
        || module.baseDir.getFileName().toString().equals(lastSegment)) {
        matches.add(module);
      }
    }
    if (matches.isEmpty()) {
      List<String> names = new ArrayList<String>();
      for (Module module : modules) names.add(module.name);
      throw new IOException("未找到模块 " + hint + "，可用模块: " + names);
    }
    if (matches.size() > 1) {
      List<String> names = new ArrayList<String>();
      for (Module module : matches) names.add(module.name);
      throw new IOException("模块名 " + hint + " 存在歧义，匹配到: " + names + "；请使用精确 artifactId");
    }
    return matches.get(0);
  }

  /**
   * 计算目标模块的依赖闭包（含自身）。
   *
   * @param modules 全部模块
   * @param target 目标模块
   * @return 依赖顺序的模块列表（目标模块在前）
   */
  static List<Module> closure(List<Module> modules, Module target) {
    List<Module> result = new ArrayList<Module>();
    Set<String> visited = new LinkedHashSet<String>();
    collectClosure(modules, target, result, visited);
    return result;
  }

  /** 深度优先收集依赖闭包。 */
  private static void collectClosure(List<Module> modules, Module target, List<Module> result, Set<String> visited) {
    if (target == null || !visited.add(target.name)) return;
    result.add(target);
    for (String depName : target.dependencyNames) {
      collectClosure(modules, byName(modules, depName), result, visited);
    }
  }

  /** 按模块名查找模块。 */
  static Module byName(List<Module> modules, String name) {
    for (Module module : modules) {
      if (module.name.equals(name)) return module;
    }
    return null;
  }

  /** 判断目录下是否存在 .java 文件。 */
  private static boolean containsJavaFile(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) return false;
    try {
      return Files.walk(dir, 32)
        .anyMatch(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java"));
    }
    catch (IOException e) {
      return false;
    }
  }

  /** 返回直接子元素中指定名称的第一个元素。 */
  private static Element childElement(Element parent, String name) {
    List<Element> elements = childElements(parent, name);
    return elements.isEmpty() ? null : elements.get(0);
  }

  /** 返回直接子元素中指定名称的全部元素。 */
  private static List<Element> childElements(Element parent, String name) {
    List<Element> result = new ArrayList<Element>();
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node.getNodeType() != Node.ELEMENT_NODE) continue;
      String nodeName = node.getNodeName();
      if (nodeName.equals(name) || nodeName.endsWith(":" + name)) {
        result.add((Element) node);
      }
    }
    return result;
  }

  /** 返回直接子元素中指定名称的文本内容。 */
  private static String childText(Element parent, String name) {
    Element element = childElement(parent, name);
    return element == null ? null : element.getTextContent();
  }
}
