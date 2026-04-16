"""
【RAG 标准流程】入库流程 (已完全适配 Labflow 全字段解析)
RAG 三要素。Load（加载）、Split（切片）、Embed（向量化）
"""

import os
import pymysql
from langchain_chroma import Chroma
from app.storage.embeddings import BGEEmbeddings
from app.storage.text_cleaner import preprocess_and_chunk

# ── Step 1: Load 加载数据 ──────────────────────────────────────────────
# ── Step 1: 修改 SQL，联表查询话题名称 ──────────────────────────
def load_notes_from_mysql():
    """Step 1: Load - 从 MySQL 读取笔记，并关联话题表获取话题名称"""
    conn = pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"),
        port=int(os.getenv("MYSQL_PORT", "3306")),
        user=os.getenv("MYSQL_USER", "root"),
        password=os.getenv("MYSQL_PASSWORD", ""),
        database=os.getenv("MYSQL_DB", "labflow"),
        charset="utf8mb4", #utf8mb4 是为了兼容表情符号
    )
    try:
        #执行 三表联查（Left Join）。将 note（笔记表）、user（用户表）、topic（话题表）关联，一次性抓取笔记内容、作者名、话题名。DictCursor 让结果以字典形式返回。
        #在 RAG 中，联表抓取的数据越全，后续 AI 检索时的上下文就越丰富。
        with conn.cursor(pymysql.cursors.DictCursor) as cursor:
            cursor.execute(
                """
                SELECT n.id, 
                       n.title, 
                       n.summary,
                       n.content, 
                       n.topic_id,
                       t.name AS topic_name,         -- 🚀 关键新增：话题名称
                       n.views,
                       n.comment_count,
                       n.user_id,
                       n.likes AS like_count,        
                       n.create_time AS created_at,  
                       n.is_deleted, 
                       u.name AS author_name         
                FROM note n
                LEFT JOIN user u ON n.user_id = u.id
                LEFT JOIN topic t ON n.topic_id = t.id  -- 🚀 关键新增：联表
                WHERE n.is_deleted = 0
                ORDER BY n.create_time DESC
                """
            )
            return cursor.fetchall()
    finally:
        conn.close()


# ── Step 2: 增强检索文本与元数据  构建检索正文（Content）。将标题、话题名、摘要、正文强行拼接   提高召回率─────────────────────────────────────
def notes_to_documents(notes):
    docs = []
    for note in notes:
        safe_summary = note.get('summary') or ""
        # 🚀 优化：将话题名称也塞进正文！这样用户搜话题名时，相关笔记能被召回
        #在正文里重复拼接标题和话题名 增加关键词权重。向量模型对头部文本更敏感，这样可以提高检索的准确度
        topic_str = f"话题：{note['topic_name'] or '未分类'}"
        raw_content = f"标题：{note['title']}\n{topic_str}\n摘要：{safe_summary}\n正文：{note['content']}"

        #构造 元数据（Metadata）。这些数据不参与向量计算，但存储在向量库中，用于后续的过滤
        metadata = {
            "note_id": str(note["id"]),
            "title": note["title"] or "",
            "topic_id": str(note["topic_id"] or ""),
            "topic_name": note["topic_name"] or "未分类",      # 🚀 存入话题名
            "views": int(note["views"] or 0),
            "comment_count": int(note["comment_count"] or 0),
            "user_id": str(note["user_id"]),
            "author": note["author_name"] or "",
            "like_count": int(note["like_count"] or 0),
            "created_at": str(note["created_at"]),
            "is_deleted": int(note.get("is_deleted", 0)),
        }

        #调用切片函数。将一篇几千字的长笔记切成多个chunk
        processed_chunks = preprocess_and_chunk(raw_content, metadata)
        docs.extend(processed_chunks)
    return docs

# ── Step 4+5: 向量化 + 存储（保持不变） ─────────────────────────────────────
def store_to_chroma(chunks, embeddings):
    # 🚀 强制锁定到项目根目录，避免不同目录下运行产生多个数据库
    base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    chroma_dir = os.path.join(base_dir, "chroma_db")

    collection_name = os.getenv("CHROMA_COLLECTION", "black_note_all")

    # 清空旧库（保证新 metadata 生效）
    import chromadb
    try:
        client = chromadb.PersistentClient(path=chroma_dir)
        client.delete_collection(collection_name)
        print(f"🗑️  旧索引已清空（metadata 将重新生成）")
    except Exception:
        pass

    #最核心的一步。调用 BGEEmbeddings 将文本转为 1024 维向量，并存入 Chroma 的 SQLite 底层文件中。
    vectorstore = Chroma.from_documents(
        documents=chunks,
        embedding=embeddings,
        persist_directory=chroma_dir,
        collection_name=collection_name,
    )

    print(f"✅ Chroma 索引库建库完成：{len(chunks)} 个 chunk")


if __name__ == "__main__":
    notes = load_notes_from_mysql()
    print(f"✅ Step 1: Load 完成 → 读取笔记：{len(notes)} 条")

    docs = notes_to_documents(notes)
    print(f"✅ Step 2: Split + 清理完成 → 有效 chunk：{len(docs)} 个")

    embeddings = BGEEmbeddings()
    store_to_chroma(docs, embeddings)
    print(f"✅ 全量建库完成！包含浏览量、评论数、摘要的完整维度数据已写入 ChromaDB！")