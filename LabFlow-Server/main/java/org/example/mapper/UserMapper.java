package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.pojo.note;
import org.example.pojo.user;
import org.example.vo.userProfile;

import java.util.List;

@Mapper
public interface UserMapper {
    @Select("select * from labflow.user where username = #{username}")
    user findByUserName(String username);

    @Insert("insert into labflow.user (username,password,name,avatar,bio,create_time,update_time) values " +
            "(#{username},#{password},#{name},#{avatar},#{bio},#{createTime},#{updateTime})")
    void register(user userRegister);

    @Select("select * from labflow.user where id = #{id}")
    userProfile getById(Integer id);

    @Select("select user.password from labflow.user where id = #{userId}")
    String getPWD(Integer userId);

    @Update("update labflow.user set name = #{name},avatar = #{avatar},bio = #{bio},update_time = #{updateTime} where id = #{id}")
    void editProfile(user newuser);

    @Update("update labflow.user set is_topic_initialized = 1,update_time = now() where id = #{id}")
    void updateTopicInitialized(Integer id);

    @Select("select * from note where user_id = #{userId} and is_deleted = 0")
    List<note> getPublished(Integer userId);

    @Select("select * from note n left join note_like nl on n.id = nl.note_id where nl.user_id = #{userId} and n.is_deleted = 0")
    List<note> getLikes(Integer userId);

    @Update("update labflow.user set password = #{encryptedNewPwd} where id = #{userId}")
    void updatePassword(Integer userId, String encryptedNewPwd);
}
