package org.example.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class userProfile {
    private Integer id;            // 主键ID
    private String username;    // 登录用户名
    @NotBlank   //会校验null、空字符串 "" 、空格字段 "  "  @NotEmpty 不能校验空格字段   @Notnull只能校验null
    private String name;        // 用户昵称
    private String avatar;      // 头像URL
    private String bio;         // 个人简介
//    @JsonIgnore  //此属性不返回给前端
//    private String password;
}
