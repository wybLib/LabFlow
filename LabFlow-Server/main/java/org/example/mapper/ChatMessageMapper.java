package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.example.pojo.ChatMessage;

@Mapper
public interface ChatMessageMapper {
    @Insert("insert into chat_message (user_id, session_id, role, content) values (#{userId},#{sessionId},#{role},#{content})")
    void ChatMessageInsert(ChatMessage chatMessage);
}
