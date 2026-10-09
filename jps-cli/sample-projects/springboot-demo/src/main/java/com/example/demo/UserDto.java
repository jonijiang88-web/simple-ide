package com.example.demo;

import lombok.Data;

/**
 * 演示用户数据传输对象
 */
@Data
public class UserDto {
    private Long id;
    private String name;
    private String email;

    /**
     * 获取用户摘要信息
     *
     * @return 格式化的摘要字符串
     */
    public String getSummary() {
        return "User(id=" + id + ", name=" + name + ", email=" + email + ")";
    }
}
