package org.example.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeMessage{
    private Integer noteId;
    private Integer userId;
    private Integer action; // 1: 异步落库(点赞)  0: 异步删除(取消点赞)
}