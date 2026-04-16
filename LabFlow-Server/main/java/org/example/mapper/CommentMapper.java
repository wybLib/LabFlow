package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.example.pojo.comment;
import org.example.vo.commentGet;

import java.util.List;

@Mapper
public interface CommentMapper {
    @Select("select c.*,u.name,u.avatar from comment c left join user u on c.user_id = u.id " +
            "where c.note_id = #{noteId} order by c.create_time desc")
    List<commentGet> GetCommentsByPage(Integer noteId);

    @Insert("insert into comment (note_id, user_id, content) values (#{noteId},#{userId},#{content})")
    void insertComment(comment newComment);
}
