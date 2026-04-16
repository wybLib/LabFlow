package org.example.pojo;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class user {
    private Integer id;            // 主键ID
    @Pattern(regexp = "^\\S{5,16}$",message = "用户名必须是5到16位非空字符")
    private String username;    // 登录用户名
    @Pattern(regexp = "^\\S{5,16}$",message = "密码必须是5到16位非空字符")
    private String password;    // 密码(Bcrypt加密)
    private String name;        // 用户昵称
    private String avatar;      // 头像URL
    private String bio;         // 个人简介
    private Integer isTopicInitialized;  //用户是否已完成首次的话题选择
    private LocalDateTime createTime; // 创建时间
    private LocalDateTime updateTime; // 更新时间
}
