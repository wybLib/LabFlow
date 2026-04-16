package org.example.listener;

import org.example.mapper.ChatMessageMapper;
import org.example.mapper.UserPersonaMapper;
import org.example.pojo.ChatMessage;
import org.example.pojo.UserPersona;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChatListener {
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private UserPersonaMapper userPersonaMapper;

    // ── 监听 1：聊天记录落库 ──
    @RabbitListener(queuesToDeclare = @Queue(name = "chat_history_queue", durable = "true"))
    // 🚀 核心修改 1：把 String 改成 Map<String, Object>。
    // Jackson 看到 JSON 对象，会自动完美地解析成一个 Map，连 Hutool 都省了！
    public void receiveAndSaveChat(Map<String, Object> payload) {
        try {
            // 直接从 Map 中取值
            String userId = String.valueOf(payload.get("user_id"));
            String sessionId = String.valueOf(payload.get("session_id"));
            String humanMsg = String.valueOf(payload.get("human_msg"));
            String aiMsg = String.valueOf(payload.get("ai_msg"));

            // 2. 构造人类的发言记录并落库
            ChatMessage userChat = new ChatMessage();
            userChat.setUserId(userId);
            userChat.setSessionId(sessionId);
            userChat.setRole("user");
            userChat.setContent(humanMsg);
            chatMessageMapper.ChatMessageInsert(userChat);

            // 3. 构造 AI 的发言记录并落库
            ChatMessage aiChat = new ChatMessage();
            aiChat.setUserId(userId);
            aiChat.setSessionId(sessionId);
            aiChat.setRole("ai");
            aiChat.setContent(aiMsg);
            chatMessageMapper.ChatMessageInsert(aiChat);

            System.out.println("✅ [MQ] 异步落库成功！User: " + userId + " | Session: " + sessionId.substring(0, Math.min(8, sessionId.length())));

        } catch (Exception e) {
            System.err.println("❌ [MQ] 解析或保存聊天记录失败：" + e.getMessage());
        }
    }

    // ── 监听 2：长期记忆(画像)落库 ──
    @RabbitListener(queuesToDeclare = @Queue(name = "user_persona_queue", durable = "true"))
    // 🚀 核心修改 2：彻底抛弃 String 和 JSONUtil，直接用实体类接收！
    // Jackson 极其智能，它会自动把 Python 发来的 JSON 映射到 UserPersona 的属性上。
    public void receiveAndSavePersona(UserPersona persona) {
        try {
            // 到这一步时，persona 已经是被 Spring 完美装配好的对象了，直接用！
            UserPersona exist = userPersonaMapper.selectById(persona.getUserId());
            if (exist == null) {
                userPersonaMapper.UserPersonaInsert(persona);
            } else {
                userPersonaMapper.updateById(persona);
            }

            System.out.println("🧠 [MQ] 用户长期记忆已同步至 MySQL！User: " + persona.getUserId());

        } catch (Exception e) {
            System.err.println("❌ [MQ] 保存长期记忆失败：" + e.getMessage());
        }
    }
}