package demo;

/** 演示由独立 JPS 编译并由 simple-ide 启动的 Java 主类。 */
public final class Main {
  /** 输出启动标记并保持运行，供 start/status/stop 端到端验证。 */
  public static void main(String[] args) {
    System.out.println("simple-ide demo started");
    try {
      Thread.currentThread().join();
    }
    catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }
}
