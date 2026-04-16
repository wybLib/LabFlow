package org.example.vo;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class userLogin {
    private Integer id;
    private String name;        // 用户昵称
    private String avatar;      // 头像URL
    private Integer isTopicInitialized;  //用户是否已完成首次的话题选择  前端根据此判断用户是否初次选择过
}
