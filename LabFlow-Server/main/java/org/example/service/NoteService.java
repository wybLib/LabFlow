package org.example.service;

import org.example.dto.notePublish;
import org.example.pojo.NoteQuery;
import org.example.pojo.PageResult;
import org.example.pojo.comment;
import org.example.pojo.note;
import org.example.vo.noteAndUser;

import java.util.List;

public interface NoteService {
    PageResult<noteAndUser> GetNotesByPage(Integer page, Integer size, Integer topicId, String keyword);

    PageResult<noteAndUser> GetNotesByPageByPOJP(NoteQuery noteQuery);

    PageResult<note> getTop50(NoteQuery noteQuery);

    void toggleLike(Integer noteId);

    PageResult<noteAndUser> ZsetgetTop50(NoteQuery noteQuery);

    void ZsettoggleLike(Integer noteId);

    noteAndUser detail(Integer noteId,Boolean isEdit);

    void publish(notePublish newNote);

    void editNote(Integer noteId,note editNote);

    void deletedNote(Integer noteId);

    void pushComments(Integer noteId,comment newComment);
}
