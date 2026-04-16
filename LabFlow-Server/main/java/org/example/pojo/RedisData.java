package org.example.pojo;

import lombok.Data;
import org.example.vo.noteAndUser;

import java.time.LocalDateTime;

@Data
public class RedisData {
    // 逻辑过期时间
    private LocalDateTime expireTime;
    // 实际的业务数据 (专门指定为 noteAndUser，方便 Hutool 反序列化)
    private noteAndUser data;
}
