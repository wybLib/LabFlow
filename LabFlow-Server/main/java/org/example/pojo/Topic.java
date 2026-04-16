package org.example.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 笔记话题(分类)表实体类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Topic {
    private Integer id;
    private String name;
    private String code;
    private String description;
    private String icon;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer isDeleted;
}