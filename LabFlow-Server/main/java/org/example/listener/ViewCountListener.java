package org.example.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.mapper.NoteMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ViewCountListener {

    @Autowired
    private NoteMapper noteMapper;

    /**
     * 极简版消费者：依赖 Spring AUTO 机制，失败直接丢弃，不实现死信队列
     */
//    @RabbitListener(queues = "view.queue") // 确保这里的队列名跟你配置的一致
//    public void handleViewMessage(Integer noteId) {
//        try {
//            // 正常落库
//            noteMapper.updateViews(noteId);
//
//            // 方法正常结束，Spring 自动发送 Ack，消息从队列中完美移除
//        } catch (Exception e) {
//            // 🚀 核心逻辑：既然不要死信队列，这里就直接打印日志，然后“生吞”异常！
//            // 只要我们不往外 throw 异常，Spring 就会以为这段代码正常跑完了。
//            // 于是它会自动发送 Ack，把这条失败的消息直接丢掉，防止无限循环报错堵死队列。
//            log.error("更新浏览量落库失败，消息已丢弃。noteId: {}, 原因: {}", noteId, e.getMessage());
//        }
//    }

    //批量落库
    // 🚀 接收参数改为 List<Integer>
//    @RabbitListener(queues = "view.queue")
    // 🚀 指定使用批量工厂
    @RabbitListener(queues = "view.queue", containerFactory = "batchQueueRabbitListenerContainerFactory")
    @Transactional(rollbackFor = Exception.class) // 🚀 加事务！
    public void handleViewMessageBatch(List<Integer> noteIds) {
        if (noteIds == null || noteIds.isEmpty()) return;

        log.info("👀 触发批量浏览量落库，当前批次大小: {}", noteIds.size());

        try {
            // 🚀 大厂极限优化：在内存中对同一篇笔记的浏览量进行合并！
            // 比如这 100 个请求里，有 80 个都是看笔记 ID=1 的。
            // 我们直接在内存里合并成 (noteId=1, 增加80次)，极大减少循环次数。
            Map<Integer, Long> viewCounts = noteIds.stream()
                    .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

            viewCounts.forEach((noteId, count) -> {
                // 如果你的 mapper 里有 updateViewsAddCount(noteId, count) 最好。
                // 如果没有，在 1 个事务里循环更新也比原来快得多
                for (int i = 0; i < count; i++) {
                    noteMapper.updateViews(noteId);
                }
            });
            log.info("✅ 成功完成浏览量批量落库");
        } catch (Exception e) {
            log.error("更新浏览量批量落库失败，消息已丢弃。原因: {}", e.getMessage());
        }
    }
}