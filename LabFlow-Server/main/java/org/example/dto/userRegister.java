package org.example.dto;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class userRegister {
    @Pattern(regexp = "^\\S{5,16}$",message = "用户名必须是5到16位非空字符")
    private String username;    // 登录用户名
    @Pattern(regexp = "^\\S{5,16}$",message = "密码必须是5到16位非空字符")
    private String password;    // 密码(Bcrypt加密)
}
