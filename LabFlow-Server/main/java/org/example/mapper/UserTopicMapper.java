package org.example.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.example.vo.topicGet;

import java.util.List;

@Mapper
public interface UserTopicMapper {
    @Delete("delete from user_topic where user_id = #{id}")
    void deleteByUserId(Integer id);

    void insertData(Integer id, List<Integer> topicsId);


    //联表sql
    @Select("select t.id,name,code from topic t left join user_topic ut on t.id = ut.topic_id where user_id = #{id} and t.is_deleted = 0;")
    List<topicGet> selectUserTopics(Integer id);
}
