package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.example.vo.topicGet;

import java.util.List;

@Mapper
public interface TopicsMapper {
    @Select("select id,name,code from topic where is_deleted = 0")
    List<topicGet> getAll();
    @Select("select name from topic where id = #{topicId} and is_deleted = 0")
    String getNameById(Integer topicId);

}
