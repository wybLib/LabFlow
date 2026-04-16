package org.example.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdatePwdDTO {
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    // 这里还可以加上 @Length(min = 6, max = 20, message = "密码长度必须在6-20位之间")
    @Pattern(regexp = "^\\S{5,16}$",message = "密码必须是5到16位非空字符")
    private String newPassword;
}