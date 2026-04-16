package org.example.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class commentGet {
    private Integer id;
    private Integer noteId;
    private Integer userId;
    private String content;
    private Integer likes;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    // 连表关联查出来的用户信息（供前端渲染头像和名字）
    private String name;
    private String avatar;
}
