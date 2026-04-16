package org.example.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class note {
    private Integer id;            // 主键ID
    private Integer userId;        // 发布者ID (逻辑外键)
    private Integer topicId; //所属话题
    private String title;       // 笔记标题
    private String summary;     // 笔记摘要
    private String content;     // 笔记正文
    private String cover;       // 封面图片URL
    private String topic;       // 所属话题
    private Integer likes;      // 点赞数
    private Integer views;      // 浏览量
    private Integer commentCount; // 评论数
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime; // 创建时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime; // 更新时间
    private Integer isDeleted;  // 逻辑删除标记(0-未删除,1-已删除)

    //非数据库字段，仅用于前后端数据传输   更规范的做法 建一个vo
    // 当前登录用户是否已点赞该笔记
    private Boolean isLiked = false;
}
