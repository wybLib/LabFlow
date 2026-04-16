package org.example.mapper;

import org.apache.ibatis.annotations.*;
import org.example.dto.notePublish;
import org.example.pojo.note;
import org.example.vo.noteAndUser;

import java.util.List;

@Mapper
public interface NoteMapper {
    //动态sql
    List<noteAndUser> GetNotesByPage(Integer topicId, String keyword);

    //查询前50点赞  前端无请求数据  可直接防止缓存击穿
    @Select("select * from note where is_deleted = 0 order by likes desc limit 50")
    List<note> GetTop50();

    // 💡 INSERT IGNORE：配合唯一索引，遇到重复插入直接返回 0，绝不报错
    //mysql 兜底
//    用户第一次点赞某笔记 → 正常插入记录
//            用户再次点赞同一条笔记
//    触发唯一索引冲突
//    IGNORE 让 MySQL 忽略错误
//    不插入数据、不抛异常
//    最终效果：一个用户只能给一条笔记点一次赞
    @Insert("INSERT IGNORE INTO note_like(note_id, user_id, create_time) VALUES(#{noteId}, #{userId}, NOW())")
    int insertIgnoreLike(Integer noteId, Integer userId);

    @Delete("DELETE FROM note_like WHERE note_id = #{noteId} AND user_id = #{userId}")
    int deleteLike(Integer noteId, Integer userId);

    // 数据库层面的总数兜底更新
    @Update("UPDATE note SET likes = likes + #{count} WHERE id = #{noteId}")
    void updateNoteLikes(Integer noteId, Integer count);

    @Select("select * from note where is_deleted = 0 order by likes desc limit 100")
    List<note> ZsetGetTop50();

    @Select("select * from note where id = #{noteId} and is_deleted = 0")
    note selectById(Integer noteId);

    @Update("update note set views = views + 1 where id = #{noteId}")
    void updateViews(Integer noteId);

    //开启 MyBatis 的主键回填 让 MyBatis 在执行完 INSERT 后，顺手把 MySQL 生成的自增 ID “回填”到你的 Java 对象中
    void publish(Integer userId, String topicName, notePublish newNote);

    void editNote(Integer noteId,note editNote);

    @Update("update note set update_time = now(),is_deleted = 1 where id = #{noteId}")
    void deletedNote(Integer noteId);

    @Select("select n.*,u.name,u.avatar from note n left join user u on n.user_id = u.id where n.is_deleted = 0 and n.id = #{noteId}")
    noteAndUser selectNoteAndUserById(Integer noteId);

    List<noteAndUser> selectNoteAndUserByIds(List<Integer> missingIds);
}
