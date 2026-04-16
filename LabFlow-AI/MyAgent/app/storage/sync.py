"""
增量同步与数据一致性
新笔记发布/编辑/删除时，同步更新 ChromaDB。
（已全面对齐 Labflow 数据库全量字段）
"""
"""
你是如何保证向量检索的实时性的？
答：设计了增量同步模块。每当笔记更新，Python 端会捕获 ID（通过 MQ 或直接调用），先在 Chroma 中执行 delete(where={"note_id": ...})，
再执行 add_documents，实现毫秒级的一致性同步。
"""

import os
from typing import Optional

import chromadb
import pymysql
from langchain_chroma import Chroma

from app.storage.embeddings import BGEEmbeddings
from app.storage.text_cleaner import preprocess_and_chunk

# ── 配置（已对齐 Labflow）────────────────────────────────
CHROMA_DIR       = os.getenv("CHROMA_DIR",       "./chroma_db")
COLLECTION_NAME  = os.getenv("CHROMA_COLLECTION", "black_note_all")

DB_CONFIG = {
    "host":     os.getenv("MYSQL_HOST",     "127.0.0.1"),
    "port":     int(os.getenv("MYSQL_PORT", "3306")),
    "user":     os.getenv("MYSQL_USER",     "root"),
    "password": os.getenv("MYSQL_PASSWORD", ""), # 🚀 你的密码
    "database": os.getenv("MYSQL_DB",       "labflow"),     # 🚀 你的数据库
    "charset":  "utf8mb4",
}

_embeddings:  BGEEmbeddings | None = None
_vectorstore: Chroma | None        = None
# 函数作用：获取向量化工具实例
def get_embeddings() -> BGEEmbeddings:
    global _embeddings
    if _embeddings is None:
        _embeddings = BGEEmbeddings()
    return _embeddings

# 函数作用：获取 Chroma 数据库连接对象
def get_vectorstore() -> Chroma:
    global _vectorstore
    if _vectorstore is None:
        _vectorstore = Chroma(
            persist_directory=CHROMA_DIR,
            embedding_function=get_embeddings(),
            collection_name=COLLECTION_NAME,
        )
    return _vectorstore

def _fetch_note(note_id: int) -> Optional[dict]:
    """从 MySQL 查询单条未删除笔记（含全量扩展字段）"""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cursor:
            # 🚀 核心对齐：抓取所有的统计字段和关联字段
            cursor.execute(
                """
                SELECT n.id, n.title, n.summary, n.content, n.topic_id,
                       n.views, n.comment_count, n.user_id,
                       n.likes AS like_count, n.create_time AS created_at,
                       u.name AS author_name
                FROM note n
                LEFT JOIN user u ON n.user_id = u.id
                WHERE n.id = %s AND n.is_deleted = 0
                """,
                (note_id,),
            )
            return cursor.fetchone()
    except Exception as e:
        print(f"查询笔记 {note_id} 失败: {e}")
        return None
    finally:
        conn.close()
# 函数作用：新增或修改笔记后的同步
def sync_single_note(note_id: int) -> bool:
    try:
        note = _fetch_note(note_id) # 1. 先从 MySQL 查出这篇笔记的最全信息
        if not note:
            print(f"笔记 {note_id} 不存在或已删除，跳过同步")
            return False

        # 🚀 拼装正文：带上摘要
        safe_summary = note.get('summary') or ""
        # 构造 AI 检索时看的“增强文本”
        raw_content = f"标题：{note['title']}\n摘要：{safe_summary}\n正文：{note['content']}"

        # 🚀 2 拼装元数据：带上所有业务指标
        metadata = {
            "note_id":       str(note["id"]),
            "title":         note["title"] or "",
            "topic_id":      str(note["topic_id"] or ""),
            "views":         int(note["views"] or 0),
            "comment_count": int(note["comment_count"] or 0),
            "user_id":       str(note["user_id"]),
            "author":        note["author_name"] or "",
            "like_count":    int(note["like_count"] or 0),
            "created_at":    str(note["created_at"]),
        }
        # 3. 切片处理
        chunks = preprocess_and_chunk(raw_content, metadata)
        if not chunks:
            print(f"笔记 {note_id} 预处理后无有效 chunk，跳过同步")
            return False

        vs = get_vectorstore()
        client = chromadb.PersistentClient(path=CHROMA_DIR)
        collection = client.get_collection(COLLECTION_NAME)
        #幂等性 (Idempotency)。如果用户修改了笔记，我们不能直接添加，否则库里会有两条（一新一旧）。
        collection.delete(where={"note_id": str(note_id)}) # 🚀 关键步骤：先删除旧的
        vs.add_documents(chunks) # 🚀 再添加新的

        from app.core.rag import _retriever_cache
        _retriever_cache.clear() # 🚀 清空内存缓存，让 AI 立刻看到新变化

        print(f"✅ 笔记 {note_id} 增量同步成功（{len(chunks)} 个有效 chunk）")
        return True

    except Exception as e:
        print(f"❌ 笔记 {note_id} 同步失败：{str(e)}")
        return False
# 函数作用：用户在前端删了笔记，这里要把向量库也清空
def delete_note_from_vectorstore(note_id: int) -> bool:
    try:
        client = chromadb.PersistentClient(path=CHROMA_DIR)
        collection = client.get_or_create_collection(COLLECTION_NAME)
        result = collection.delete(where={"note_id": str(note_id)})
        deleted_count = result.get("deleted", 0) if isinstance(result, dict) else 0

        if deleted_count > 0:
            print(f"✅ 笔记 {note_id} 已删除（移除 {deleted_count} 个 chunk）")
        else:
            print(f"⚠️  笔记 {note_id} 在向量库中无匹配 chunk")

        from app.core.rag import _retriever_cache
        _retriever_cache.clear()
        return True

    except Exception as e:
        print(f"❌ 删除笔记 {note_id} 失败：{str(e)}")
        return False