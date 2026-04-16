package org.example.controller;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.dto.notePublish;
import org.example.pojo.*;
import org.example.service.CommentService;
import org.example.service.NoteService;
import org.example.vo.commentGet;
import org.example.vo.noteAndUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notes")
@Slf4j
@Valid
public class NoteController {
    @Autowired
    private NoteService noteService;
    @Autowired
    private CommentService commentService;

//    @GetMapping("/notes")
//    public Result GetNotesByPage(Integer page,Integer size,@RequestParam(required = false) Integer topicId,@RequestParam(required = false) String keyword){
//        PageResult<note> notes = noteService.GetNotesByPage(page,size,topicId,keyword);
//        return Result.success(notes);
//    }

    @GetMapping
    public Result GetNotesByPageByPOJP(NoteQuery noteQuery){
        PageResult<noteAndUser> notes = noteService.GetNotesByPageByPOJP(noteQuery);
        return Result.success(notes);
    }

    //让推荐页面也分页  并且可以搜索
    //推荐页的内容存入了redis  可以直接在内存中进行搜索和分页
//    @GetMapping("/notes/recommend")
//    public Result getRecommend(NoteQuery noteQuery){
//        PageResult<note> top50 = noteService.getTop50(noteQuery);
//        return Result.success(top50);
//    }
//
//    @PostMapping("/notes/{id}/like")
//    public Result likeNote(@PathVariable("id") Integer noteId) {
//        noteService.toggleLike(noteId);
//        return Result.success();
//    }

    //用zset实现
    @GetMapping("/recommend")
    public Result ZsetgetRecommend(NoteQuery noteQuery){
        PageResult<noteAndUser> top50 = noteService.ZsetgetTop50(noteQuery);
        return Result.success(top50);
    }

    @PostMapping("/{id}/like")
    public Result ZsetlikeNote(@PathVariable("id") Integer noteId) {
        noteService.ZsettoggleLike(noteId);
        return Result.success();
    }

    //给详情接口加一个“是否需要增加浏览量”的标记 防止哪怕只是看一眼旧数据用来编辑，也被算作了一次“真实浏览”。
    @GetMapping("/{id}")
    public Result detail(@PathVariable("id") Integer noteId,
    @RequestParam(required = false, defaultValue = "false") Boolean isEdit){
        noteAndUser noteDetail = noteService.detail(noteId,isEdit);
        return Result.success(noteDetail);
    }

    @GetMapping("/{noteId}/comments")
    public Result getComments(
            @PathVariable Integer noteId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {

        PageResult<commentGet> pageResult = commentService.getCommentsByNoteId(noteId, page, size);
        return Result.success(pageResult);
    }

    @PostMapping
    //当 @RequestBody 接收的 JSON 中某个字段缺失时，Spring 会将该字段设置为 null。
    public Result publish(@RequestBody notePublish newNote){
        noteService.publish(newNote);
        return Result.success();
    }

    @PutMapping("/{id}")   //与前端接口路径参数名一致
    public Result editNote(@PathVariable("id") Integer noteId,@RequestBody note editNote){
        noteService.editNote(noteId,editNote);
        return Result.success();
    }

    //更新 DB -> 尝试删除缓存 -> 失败则发给 MQ -> 消费者不断重试删除。
    @DeleteMapping("/{id}")
    public Result deletedNote(@PathVariable("id") Integer noteId){
        noteService.deletedNote(noteId);
        return Result.success();
    }

    //评论功能
    @PostMapping("/{id}/comments")
    public Result pushComments(@PathVariable("id") Integer noteId,@RequestBody comment newComment){
        noteService.pushComments(noteId,newComment);
        return Result.success();
    }
}
