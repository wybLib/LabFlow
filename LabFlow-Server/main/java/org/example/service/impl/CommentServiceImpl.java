package org.example.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.example.mapper.CommentMapper;
import org.example.pojo.PageResult;
import org.example.service.CommentService;
import org.example.vo.commentGet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CommentServiceImpl implements CommentService {
    @Autowired
    private CommentMapper commentMapper;
    @Override
    public PageResult<commentGet> getCommentsByNoteId(Integer noteId, Integer page, Integer size) {
        //设置分页参数
        PageHelper.startPage(page,size);
        //查询  这个rows就是一个Page对象 但是同时Page也是List的实现类 所以可以封装  体现了多态
        //这里适配前端  把rows改名list
        List<commentGet> list = commentMapper.GetCommentsByPage(noteId);
        //解析结果并返回
        Page<commentGet> p = (Page<commentGet>) list;  //从Page对象中获取total和数据列表
        return new PageResult<commentGet>(p.getTotal(),p.getResult());
    }
}
