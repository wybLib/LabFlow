package org.example.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private Long id;
    // 用户ID
    private String userId;
    // 会话ID (用于把同一次聊天的上下文串起来)
    private String sessionId;
    // 角色：只能是 "user" 或 "ai"
    private String role;
    // 聊天的具体内容
    private String content;
    // 创建时间
    private Date createTime;
}
