package org.example.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class notePublish {
    private Integer id;   //主键回填 用于给ai查询
    private Integer topicId; //所属话题
    private String title;       // 笔记标题
    private String summary;     // 笔记摘要
    private String content;     // 笔记正文
    private String cover;       // 封面图片URL
}
