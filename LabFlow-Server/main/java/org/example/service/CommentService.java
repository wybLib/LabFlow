package org.example.service;

import org.example.pojo.PageResult;
import org.example.vo.commentGet;

public interface CommentService {
    PageResult<commentGet> getCommentsByNoteId(Integer noteId, Integer page, Integer size);
}
