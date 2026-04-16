package org.example.listener;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.example.config.RabbitConfig;
import org.example.mapper.CommentMapper;
import org.example.pojo.comment;
import org.example.webSocket.NoteOnlineWebSocket;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class CommentMQListener {

    @Autowired
    private CommentMapper commentMapper;

    // ====================================================
    // 任务 1：真正的硬盘写入者 (监听持久化落库队列)
    // ====================================================
//    @RabbitListener(queues = RabbitConfig.COMMENT_INSERT_QUEUE)
//    public void handleCommentInsert(comment newComment) {
//        log.info("MQ 正在异步执行评论落库, noteId: {}", newComment.getNoteId());
//        // 调用你的 Mapper 写入 MySQL
//        commentMapper.insertComment(newComment);
//    }

    //批量落库
//    @RabbitListener(queues = RabbitConfig.COMMENT_INSERT_QUEUE)
    @RabbitListener(queues = RabbitConfig.COMMENT_INSERT_QUEUE, containerFactory = "batchQueueRabbitListenerContainerFactory")
    @Transactional(rollbackFor = Exception.class) // 🚀 加事务！
    public void handleCommentInsertBatch(List<comment> comments) {
        if (comments == null || comments.isEmpty()) return;

        log.info("📝 触发批量评论落库，当前批次大小: {}", comments.size());
        for (comment newComment : comments) {
            commentMapper.insertComment(newComment);
        }
        log.info("✅ 成功完成 {} 条评论的批量写入", comments.size());
    }

    // ====================================================
    // 任务 2：集群推送广播兵 (监听本机专有的 AnonymousQueue)
    // ====================================================
    @RabbitListener(queues = "#{commentBroadcastQueue.name}")
    public void handleCommentBroadcast(String jsonStr) {
        Map<String, Object> msg = JSONUtil.toBean(jsonStr, Map.class);
        String noteId = msg.get("noteId").toString();
        Object commentData = msg.get("data");

        log.info("MQ 收到新评论广播，准备通过本机的 WebSocket 推送给前端, noteId: {}", noteId);
        
        // 调用 WebSocket 的静态方法，将新评论“精准投喂”给正在看这篇笔记的用户
        NoteOnlineWebSocket.broadcast(noteId, "NEW_COMMENT", commentData);
    }
}