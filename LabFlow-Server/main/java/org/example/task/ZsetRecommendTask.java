package org.example.task;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.example.mapper.NoteMapper;
import org.example.pojo.note;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
//这里我们采用的5分钟定时任务
//还可以采用：逻辑过期（惰性刷新）：
//机制：没人访问时，后台什么都不做。只有当某个用户访问时，代码发现“哎呀，数据过期了”，它才会立刻把旧数据扔给这个用户，同时偷偷在后台开一个单次线程去查数据库更新 Redis。
//特点：被动触发（懒加载）。只有在需要的时候才消耗资源。
//缺点：第一个触发过期的那个倒霉用户，看到的是旧数据；而且需要维护线程池和复杂的抢锁逻辑。
public class ZsetRecommendTask {

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //记录了 noteId 和 分数(点赞数)。决定了笔记排在第几名
    public static final String RANK_ZSET_KEY = "labflow:notes:recommend:zset";
    //记录了 noteId 和 完整的 JSON 数据 (标题、封面、摘要等)。
    public static final String DETAIL_HASH_KEY = "labflow:notes:recommend:detail";
    //锁
    public static final String TASK_LOCK_KEY = "labflow:lock:task:recommend";

    //对于同时用 Scheduled 和 PostConstruct注解   单机部署问题不大
    // 多台机器（集群模式），比如 3 台服务器同时启动，或者到了整点 5 分钟。这 3 台机器会同时去查 MySQL，然后同时去覆盖 Redis。
    //此时必须使用分布式锁
    @Scheduled(cron = "0 0/5 * * * ?")
    @PostConstruct
    public void refreshRecommendPool() {
        // 1. 加分布式锁：防止多实例重复执行
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(TASK_LOCK_KEY, "1", 1, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(isLock)) return;

        try {
            // 捞出排名前 100 的笔记作为推荐候选池
            List<note> topList = noteMapper.ZsetGetTop50(); // 建议 SQL 改成 LIMIT 100，池子大点更好

            if (topList != null && !topList.isEmpty()) {
                // 使用 Pipeline 批量写入redis提高性能
                stringRedisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                    for (note n : topList) {
                        String noteIdStr = n.getId().toString();
                        // 1. 存入 ZSET，存笔记ID + 分数（点赞数）
                        connection.zSetCommands().zAdd(RANK_ZSET_KEY.getBytes(), n.getLikes(), noteIdStr.getBytes());
                        // 2. 存入Hash：存笔记完整详情
                        connection.hashCommands().hSet(DETAIL_HASH_KEY.getBytes(), noteIdStr.getBytes(), JSONUtil.toJsonStr(n).getBytes());
                    }
                    return null;
                });

                // 给这两个大 Key 设置个稍微长一点的过期时间兜底
                stringRedisTemplate.expire(RANK_ZSET_KEY, 1, TimeUnit.HOURS);
                stringRedisTemplate.expire(DETAIL_HASH_KEY, 1, TimeUnit.HOURS);

                log.info("🔥 基于 ZSET 的推荐底座预热完成, 加载 {} 条", topList.size());
            }
        } catch (Exception e) {
            log.error("预热任务异常", e);
        } finally {
            stringRedisTemplate.delete(TASK_LOCK_KEY); // 释放锁
        }
    }
}