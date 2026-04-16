package org.example.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteQuery {
    private Integer page = 1;   //默认值
    private Integer size = 12;  //前端页面3个一排 展示4排  到下一页 美观
    private Integer topicId;
    private String keyword;
}
