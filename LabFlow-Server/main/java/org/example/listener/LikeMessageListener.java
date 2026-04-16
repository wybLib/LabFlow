package org.example.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.config.RabbitConfig;
import org.example.mapper.NoteMapper;
import org.example.dto.LikeMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
public class LikeMessageListener {
    @Autowired
    private NoteMapper noteMapper;

//    @RabbitListener(queues = RabbitConfig.LIKE_QUEUE)
//    @Transactional(rollbackFor = Exception.class)
//    public void handleLikeMessage(LikeMessage msg) {
//        // 由于 Redis 已经在前端挡住了所有重复流量，这里的 MQ 消息都是真正需要落库的。
//        // 即便 MQ 服务器网络抖动导致同一条消息发了两次，底层的 INSERT IGNORE 和影响行数判断也能完美兜底。
//        try {
//            if (msg.getAction() == 1) {
//                int rows = noteMapper.insertIgnoreLike(msg.getNoteId(), msg.getUserId());
//                if (rows > 0) {
//                    noteMapper.updateNoteLikes(msg.getNoteId(), 1);
//                    log.info("异步落库：笔记 {} 点赞 +1", msg.getNoteId());
//                }
//            } else {
//                int rows = noteMapper.deleteLike(msg.getNoteId(), msg.getUserId());
//                if (rows > 0) {
//                    noteMapper.updateNoteLikes(msg.getNoteId(), -1);
//                    log.info("异步落库：笔记 {} 取消点赞 -1", msg.getNoteId());
//                }
//            }
//        } catch (Exception e) {
//            log.error("数据库写入异常，触发 MQ 自动重试机制", e);
//            throw e; // 抛出异常，让 Spring AMQP 触发 NACK，消息重回队列
//        }
//    }


    //批量落库
    // 🚀 接收参数从 LikeMessage 改成了 List<LikeMessage>
//    @RabbitListener(queues = RabbitConfig.LIKE_QUEUE)
    // 🚀 指定使用批量工厂
    @RabbitListener(queues = RabbitConfig.LIKE_QUEUE, containerFactory = "batchQueueRabbitListenerContainerFactory")
    @Transactional(rollbackFor = Exception.class)
    public void handleLikeMessageBatch(List<LikeMessage> messages) {
        if (messages == null || messages.isEmpty()) return;

        log.info("📦 触发批量点赞落库，当前批次大小: {}", messages.size());

        try {
            // 🚀 核心优化：这 100 次循环全包裹在【1个事务】中！
            // 以前 100 条消息需要开启和提交 100 次数据库事务，现在只需要 1 次，性能提升极其恐怖！
            for (LikeMessage msg : messages) {
                if (msg.getAction() == 1) {
                    int rows = noteMapper.insertIgnoreLike(msg.getNoteId(), msg.getUserId());
                    if (rows > 0) {
                        noteMapper.updateNoteLikes(msg.getNoteId(), 1);
                    }
                } else {
                    int rows = noteMapper.deleteLike(msg.getNoteId(), msg.getUserId());
                    if (rows > 0) {
                        noteMapper.updateNoteLikes(msg.getNoteId(), -1);
                    }
                }
            }
            log.info("✅ 成功完成 {} 条点赞记录的批量写入", messages.size());
        } catch (Exception e) {
            log.error("数据库批量写入异常，触发 MQ 自动重试机制", e);
            throw e;
        }
    }
}