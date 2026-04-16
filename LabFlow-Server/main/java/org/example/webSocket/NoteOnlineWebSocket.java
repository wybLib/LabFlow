package org.example.webSocket;

import cn.hutool.json.JSONUtil;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
//声明这是一个 WebSocket 端点
@ServerEndpoint("/ws/note/online/{noteId}") // 路由带上 noteId，实现不同笔记不同房间
public class NoteOnlineWebSocket {

    // 记录每篇笔记当前的在线人数：Key -> noteId, Value -> 人数计数器  AtomicInteger原子整数计数器
//    为什么用 AtomicInteger？ 1.线程安全，多线程环境下保证计数正确  2.提供原子操作：incrementAndGet()、decrementAndGet()
    //无法实现集群上的人数统计
//    private static final ConcurrentHashMap<String, AtomicInteger> onlineCountMap = new ConcurrentHashMap<>();

    //redis存储
    public static final String REDIS_ONLINE_KEY = "note:online:count:";

    // 存放所有存活的 Session (为了广播发消息)  key Session ID（WebSocket 会话唯一标识）  Value	WebSocket Session 对象
    //全局 Map<sessionId, Session> 存全站几万个连接。每当有一条评论，就要把几万个连接全部遍历一遍，用 contains 去判断他在不在看这篇笔记。CPU 瞬间爆炸。
//    private static final ConcurrentHashMap<String, Session> sessionMap = new ConcurrentHashMap<>();

    // Key: noteId (房间号), Value: 这个房间里的所有 Session(key sessionId value session)  摒弃低效的全站Session遍历  用二维哈希表存储
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Session>> roomMap = new ConcurrentHashMap<>();

    // WebSocket 是多实例的，必须通过静态方法注入 Spring Bean
    private static StringRedisTemplate stringRedisTemplate;
    @Autowired
    public void setStringRedisTemplate(StringRedisTemplate redis) {
        NoteOnlineWebSocket.stringRedisTemplate = redis;
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("noteId") String noteId) {
        // 1. 存储 Session
//        sessionMap.put(session.getId(), session);
        roomMap.computeIfAbsent(noteId, k -> new ConcurrentHashMap<>()).put(session.getId(), session);

//        // 2. 获取或创建计数器，并原子性 +1   computeIfAbsent类似于setnx   不存在就创建一个
//        //在 onlineCountMap 中查找 noteId 对应的计数器 若存在则直接返回已存在的 AtomicInteger 对象
//        //若不存在 执行 k -> new AtomicInteger(0) 创建新计数器，存入 Map，然后返回
//        AtomicInteger counter = onlineCountMap.computeIfAbsent(noteId, k -> new AtomicInteger(0));
//        int count = counter.incrementAndGet();  //原子自增  先加1，再返回新值
//
//        log.info("🚀 访客进入笔记 {}, 当前在线: {}", noteId, count);
//        // 4. 广播最新人数给同笔记的所有用户
//        broadcast(noteId, "ONLINE_COUNT" ,count);

        //redis存储
        // 🚀 Redis 原子累加 (全局唯一真实人数)  increment 命令是原子且“懒惰”的  若key不存在 则创建并初始化值为0再+1
        Long count = stringRedisTemplate.opsForValue().increment(REDIS_ONLINE_KEY + noteId);
        //即使这台 Tomcat 宿主机突然断电，没有执行 onClose 里的 decrement，Redis 里的计数器也不会变成永久的垃圾数据（内存泄漏），24小时后它会自动清理
        stringRedisTemplate.expire(REDIS_ONLINE_KEY + noteId, 24, TimeUnit.HOURS);
        log.info("🚀 访客进入笔记 {}, 当前全局在线: {}", noteId, count);

        // 【单机版直接推送】：因为不走 MQ，这里直接查出全局人数，然后发给本机连着的这批用户
        broadcast(noteId, "ONLINE_COUNT", count != null ? count.intValue() : 1);
    }

    @OnClose
    public void onClose(Session session, @PathParam("noteId") String noteId) {
        // 1. 移除 Session
//        sessionMap.remove(session.getId());
        // 从指定房间移除
        ConcurrentHashMap<String, Session> room = roomMap.get(noteId);
        if (room != null) {
            room.remove(session.getId());
            if (room.isEmpty()) roomMap.remove(noteId); // 房间空了就销毁
        }

//        // 2. 获取计数器
//        AtomicInteger counter = onlineCountMap.get(noteId);
//        if (counter != null) {
//            int count = counter.decrementAndGet();  // 人数 -1
//            log.info("👋 访客离开笔记 {}, 当前在线: {}", noteId, count);
//
//            if (count <= 0) {
//                onlineCountMap.remove(noteId); // 没人了就清理内存
//            } else {
//                broadcast(noteId, "ONLINE_COUNT" ,count);  // 还有人在，广播更新
//            }
//        }

        //redis实现
        // 🚀 Redis 原子递减
        Long count = stringRedisTemplate.opsForValue().decrement(REDIS_ONLINE_KEY + noteId);
        if (count != null && count <= 0) {
            stringRedisTemplate.delete(REDIS_ONLINE_KEY + noteId);
        }
        log.info("👋 访客离开笔记 {}, 当前全局在线: {}", noteId, count);

        // 单机直接推送最新人数
        broadcast(noteId, "ONLINE_COUNT", (count != null && count > 0) ? count.intValue() : 0);
    }

    @OnError  //发生错误时
    public void onError(Session session, Throwable error) {
        log.error("WebSocket 发生错误", error);
    }

    /**
     * 广播给特定房间(noteId)的所有人
     * type表明当前广播的是什么消息  分为在线人数统计和实时评论推送
     */
    //若需外部调用  则public static....
    public static void broadcast(String noteId, String type, Object data) {
        // 1. 构建消息
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", type);
        msg.put(type.equals("ONLINE_COUNT") ? "count" : "comment", data);
        String jsonMsg = JSONUtil.toJsonStr(msg);  // 转为 JSON: {"type":"ONLINE_COUNT","count":5}

//        // 遍历所有连接
//        for (Session s : sessionMap.values()) {
//            //筛选出当前在看这篇 noteId 的 Session
//            if (s.getRequestURI().toString().contains("/ws/note/online/" + noteId)) {
//                try {
//                    // 4. 发送消息  获取同步发送器（阻塞式） 再发消息
//                    // 同步发送（阻塞，等待发送完成）
//                    // 异步发送（非阻塞，立即返回）
////                    s.getAsyncRemote().sendText(jsonMsg);
////                    s.getBasicRemote().sendText(jsonMsg);
//                    //加上回调处理失败的异步
//                    s.getAsyncRemote().sendText(jsonMsg, result -> {
//                        if (!result.isOK()) {
//                            log.error("WebSocket推送异常: {}", result.getException().getMessage());
//                        }
//                    });
//                } catch (Exception e) {
//                    log.error("推送失败", e);
//                }
//            }
//        }
        // O(1) 极速定位到房间，只遍历这个房间里的人，绝不殃及池鱼！
        ConcurrentHashMap<String, Session> room = roomMap.get(noteId);
        if (room != null) {
            for (Session s : room.values()) {
                s.getAsyncRemote().sendText(jsonMsg, result -> {
                    if (!result.isOK()) {
                            log.error("WebSocket推送异常: {}", result.getException().getMessage());
                       }
                        });
            }
        }
    }
}