package com.example.demo;

/**
 * 演示应用主类
 */
public class DemoApplication {

    /**
     * 演示主入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        UserDto user = new UserDto();
        user.setId(1L);
        user.setName("Antigravity");
        user.setEmail("test@example.com");
        System.out.println("Hello, " + user.getSummary());
    }
}
