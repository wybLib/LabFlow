package org.example.runner;

import lombok.extern.slf4j.Slf4j;
import org.example.mapper.NoteMapper;
import org.example.pojo.note;
import org.example.service.impl.NoteServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 🚀 系统缓存预热器
 * 实现 ApplicationRunner 接口，确保在 Spring Boot 完全启动完毕后执行一次
 */
@Slf4j
@Component
public class CacheWarmerRunner implements ApplicationRunner {

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public static final String WARMER_LOCK_KEY = "labflow:lock:warmer:recommend";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 1. 加分布式锁：防止多台服务器集群同时启动时重复预热
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(WARMER_LOCK_KEY, "1", 1, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(isLock)) {
            log.info("其它节点正在执行预热，跳过...");
            return;
        }

        try {
            // 2. 去数据库捞出历史排名前 100 的笔记作为初始池子
            List<note> topList = noteMapper.ZsetGetTop50();

            if (topList != null && !topList.isEmpty()) {
                // 3. 批量打入 Redis ZSET
                stringRedisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                    for (note n : topList) {
                        String noteIdStr = n.getId().toString();
                        // 引用 NoteServiceImpl 里的常量
                        connection.zSetCommands().zAdd(NoteServiceImpl.RANK_ZSET_KEY.getBytes(), n.getLikes(), noteIdStr.getBytes());
                    }
                    return null;
                });

                log.info("🔥 系统冷启动：ZSET 推荐底座初始预热完成, 加载 {} 条", topList.size());
            }
        } catch (Exception e) {
            log.error("ZSET预热异常", e);
        } finally {
            stringRedisTemplate.delete(WARMER_LOCK_KEY); // 释放锁
        }
    }
}