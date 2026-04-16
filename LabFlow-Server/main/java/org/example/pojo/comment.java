package org.example.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class comment {
    private Integer id;            // 主键ID
    private Integer noteId;        // 笔记ID (逻辑外键)
    private Integer userId;        // 评论者ID (逻辑外键)
    private String content;     // 评论内容
    private Integer likes;       //评论点赞数
    private LocalDateTime createTime; // 创建时间
}
