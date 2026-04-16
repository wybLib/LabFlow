package org.example.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.config.RabbitConfig;
import org.example.task.ZsetRecommendTask;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CacheCompensationListener {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 监听缓存补偿队列  更新 DB -> 尝试删除缓存 -> 失败则发给 MQ -> 消费者不断重试删除。
     */
//    @RabbitListener(queues = RabbitConfig.CACHE_COMPENSATION_QUEUE)
//    public void handleCacheCompensation(Integer noteId) {
//        try {
//            log.info("🚀 MQ补偿机制启动：正在异步重试清理笔记 {} 的缓存...", noteId);
//
//            // 再次尝试删除 Redis 相关的 Keys   注意一定要删齐全
//            stringRedisTemplate.delete("note:detail:" + noteId);
//            stringRedisTemplate.delete("note:count:view:" + noteId);
//            stringRedisTemplate.delete("note:count:like:" + noteId);
//            stringRedisTemplate.delete("note:likes:" + noteId);
//            stringRedisTemplate.opsForZSet().remove(ZsetRecommendTask.RANK_ZSET_KEY, noteId.toString());
//            stringRedisTemplate.opsForHash().delete(ZsetRecommendTask.DETAIL_HASH_KEY, noteId.toString());
//
//            // 如果顺利走到这里，说明补偿成功，Spring 自动 ACK，消息移出队列
//            log.info("✅ MQ补偿机制：笔记 {} 的缓存已成功清理！", noteId);
//
//        } catch (Exception e) {
//            log.error("❌ MQ补偿机制重试失败，将触发 Spring AMQP 再次重试，noteId: {}", noteId, e);
//            // 抛出异常，让 Spring 自动 NACK 并重试（直到成功或进入死信队列）
//            throw e;
//        }
//    }
    @RabbitListener(queues = RabbitConfig.CACHE_COMPENSATION_QUEUE)
    public void handleCacheCompensation(Integer noteId) {
        try {
            log.info("🚀 MQ补偿机制启动：正在异步重试清理笔记 {} 的缓存...", noteId);

            // 再次尝试删除 Redis 相关的 Keys   注意一定要删齐全
            stringRedisTemplate.delete("note:detail:" + noteId);
            stringRedisTemplate.delete("note:count:view:" + noteId);
            stringRedisTemplate.delete("note:count:like:" + noteId);
            stringRedisTemplate.delete("note:likes:" + noteId);

            // 🚀 只需清理 ZSet 排名
            stringRedisTemplate.opsForZSet().remove(ZsetRecommendTask.RANK_ZSET_KEY, noteId.toString());

            // 如果顺利走到这里，说明补偿成功，Spring 自动 ACK，消息移出队列
            log.info("✅ MQ补偿机制：笔记 {} 的缓存已成功清理！", noteId);

        } catch (Exception e) {
            log.error("❌ MQ补偿机制重试失败，将触发 Spring AMQP 再次重试，noteId: {}", noteId, e);
            // 抛出异常，让 Spring 自动 NACK 并重试（直到成功或进入死信队列）
            throw e;
        }
    }
}