package org.example.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class noteLike {
    private Integer id;            // 主键ID
    private Integer noteId;        // 笔记ID
    private Integer userId;        // 用户ID
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime; // 点赞时间
    //在note_like表内  执行了这个的
//    核心护城河：防止同一个用户对同一篇笔记产生多条点赞记录
//    ALTER TABLE note_like ADD UNIQUE INDEX uk_note_user (note_id, user_id);
}
