package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.pojo.UserPersona;

@Mapper
public interface UserPersonaMapper {
    @Insert("insert into user_persona (user_id, persona_text) values (#{userId},#{personaText})")
    void UserPersonaInsert(UserPersona userPersona);

    @Select("select * from user_persona where user_id = #{userId}")
    UserPersona selectById(String userId);

    @Update("update user_persona set persona_text = #{personaText},update_time = now() where user_id = #{userId}")
    void updateById(UserPersona persona);
}
