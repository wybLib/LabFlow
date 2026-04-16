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
public class RecommendTask {

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public static final String TOP50_BASE_KEY = "labflow:notes:recommend:top50:base";
    // 分布式锁的 KEY
    public static final String TASK_LOCK_KEY = "labflow:lock:task:recommend";

    //对于同时用 Scheduled 和 PostConstruct注解   单机部署问题不大
    // 多台机器（集群模式），比如 3 台服务器同时启动，或者到了整点 5 分钟。这 3 台机器会同时去查 MySQL，然后同时去覆盖 Redis。
    //此时必须使用分布式锁
    @Scheduled(cron = "0 0/5 * * * ?")  //每 5 分钟执行 1 次
    @PostConstruct   //项目启动瞬间执行 1 次
    public void refreshTop50Notes() {
        // 1. 尝试获取分布式锁 (防多节点并发执行，锁的过期时间设为 1 分钟即可)
        // setIfAbsent 在 Redis 里对应 SETNX 命令
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(TASK_LOCK_KEY, "1", 1, TimeUnit.MINUTES);

        if (Boolean.FALSE.equals(isLock)) {
            // 如果没抢到锁，说明集群里别的机器正在执行，或者刚启动时已经被触发过，直接静默退出
            log.debug("⏭️ 另一台服务器正在预热榜单，本节点跳过");
            return;
        }

        // 2. 抢到锁后，执行核心逻辑，并加上全局 Try-Catch 兜底
        try {
            List<note> top50List = noteMapper.GetTop50();

            if (top50List != null && !top50List.isEmpty()) {
                // 刷新底座缓存
                stringRedisTemplate.opsForValue().set(TOP50_BASE_KEY, JSONUtil.toJsonStr(top50List), 10, TimeUnit.MINUTES);
                log.info("🔥 Top 50 推荐底座预热完成, 共加载 {} 条记录", top50List.size());

                // 核心心法：只兜底，不覆盖。保护 Redis 里最实时的高频计数值！
                for (note n : top50List) {
                    String countKey = "note:count:like:" + n.getId();
                    stringRedisTemplate.opsForValue().setIfAbsent(countKey, n.getLikes().toString(), 24, TimeUnit.HOURS);
                }
            }
        } catch (Exception e) {
            // 捕获所有异常，防止 @PostConstruct 阻塞项目启动
            log.error("❌ Top 50 推荐榜单预热任务执行失败: ", e);
        } finally {
            // 3. 释放锁 (防止任务执行过快，锁还没过期，下一次任务就卡住了。用 try-finally 保证必执行)
            // 可选：如果不删锁，也可以等它 1 分钟后自然过期，这样连 1 分钟内的频繁启动也能防住
            stringRedisTemplate.delete(TASK_LOCK_KEY);
        }
    }
}