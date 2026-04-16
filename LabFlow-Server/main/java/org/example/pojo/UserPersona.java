package org.example.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPersona {
    // ⚠️ 注意：这里的逐渐不是自增 ID，而是 user_id！每个用户只有唯一的一条画像记录。
    // 🚀 告诉 Jackson：当你看到 JSON 里的 "user_id" 时，请把它赋值给 userId
    @JsonProperty("user_id")
    private String userId;
    // AI 提取的画像文本
    @JsonProperty("persona_text")
    private String personaText;
    // 更新时间
    private Date updateTime;
}
