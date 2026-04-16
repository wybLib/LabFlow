"""
app/core/tools.py - 重构版 v10.0 (终极完全体：深度融合 Topic 话题体系与评论分析)
"""

import asyncio
import json
import os

from dotenv import load_dotenv
from langchain.tools import tool
from langchain_core.runnables import RunnableConfig
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine

from app.core.rag import make_rag_retriever, rerank_docs

load_dotenv()
# 🚀 创建异步 MySQL 连接池
_ASYNC_ENGINE = create_async_engine(
    os.getenv(
        "MYSQL_ASYNC_URL",
        "mysql+aiomysql://root:password@localhost/labflow",
    ),
    pool_size=5,
    max_overflow=10,
)

#这是一个工厂函数，把所有工具打包在一起返回。传入向量数据库 vectorstore 备用。
def make_tools(vectorstore):

    @tool
    async def get_current_user_info(config: RunnableConfig = None) -> str:
        #三重引号里的注释（Docstring），翻译成大模型能看懂的 JSON 说明书。大模型完全是通过这段注释来决定要不要调用这个工具的！ 所以这行注释就是你写给大模型的提示词。
        """获取当前正在与你对话的用户的个人信息（当用户问“我是谁”、“我的资料”时调用）。"""
        user_id = config.get("configurable", {}).get("user_id")
        if not user_id:
            return json.dumps({"error": "未获取到用户凭证"}, ensure_ascii=False)

        try:
            async with _ASYNC_ENGINE.connect() as conn:
                # 执行 SQL 查询当前用户是谁   全异步
                row = (await conn.execute(
                    text("SELECT id, name FROM user WHERE id = :uid"),
                    {"uid": user_id}
                )).fetchone()

            if row:
                # 把查到的结果变成 JSON 字符串还给大模型
                return json.dumps({"user_id": row[0], "name": row[1]}, ensure_ascii=False)
            return json.dumps({"error": "未在数据库中找到该用户"}, ensure_ascii=False)
        except Exception as e:
            return json.dumps({"error": f"查询用户信息失败：{str(e)}"}, ensure_ascii=False)

    @tool
    #为什么需要 RunnableConfig 大模型在调用时，会自己决定传入什么参数。如果大模型“发神经”或者被恶意用户诱导，它可能会传：{"query": "我的日记", "user_id": "admin"}。这就发生了越权！大模型竟然可以自己决定去查谁的数据！
    #LangChain 和 LangGraph 中引入了 RunnableConfig 当函数的参数带上 config: RunnableConfig 时，LangChain 会在底层做两件事：
    #1.对大模型隐身：大模型看这个工具的说明书时，根本看不到 config 这个参数。2.打通后端暗道：config 是直接从你的 Python 主程序（比如 main.py）里，沿着 LangGraph 的流水线，一路偷偷传递到工具里的。
    #从那个“暗道”里，把后端校验过的、绝对安全的 user_id 掏出来用。全程大模型毫无参与，彻底杜绝了安全隐患。
    async def search_notes(
            query: str,
            scope: str = "global",
            limit: int = 8,
            config: RunnableConfig = None,
    ) -> str:
        #给了大模型 控制权。大模型看到你的注释，它如果在聊天时分析出用户在问“我自己的笔记”，它就会聪明地把 scope 设置为 "personal" 传进来
        """
        用自然语言语义检索笔记内容。
        :param scope: "global" 表示检索全站；"personal" 表示只检索当前用户自己的笔记。
        """
        user_id = config.get("configurable", {}).get("user_id") if scope == "personal" else None

        try:
            retriever = make_rag_retriever(vectorstore, user_id=user_id) # 调用我们在 rag.py 里写好的混合检索器
            # 🚀 async 高阶用法：将同步阻塞代码扔进线程池
            #retriever.invoke (检索向量) 和 rerank_docs (大模型重排序) 是极其耗费 CPU 的同步操作（会一直卡着等）。
# 如果直接在 async 函数里运行它们，整个服务器都会被卡住。asyncio.to_thread 的作用是：把这些脏活累活扔到后台的其他打工仔（线程池）那里去跑，主服务员继续去接待其他客人。跑完再把结果拿回来。
            raw_docs = await asyncio.to_thread(retriever.invoke, query)
            reranked = await asyncio.to_thread(rerank_docs, query, raw_docs, top_n=limit)

            # 数据清洗：去除重复的笔记，并截断太长的正文
            seen = set()
            notes = []
            for doc in reranked:
                note_id = doc.metadata.get("note_id")
                if note_id and note_id not in seen: # 防止重复
                    seen.add(note_id)
                    meta = doc.metadata
                    notes.append({
                        "note_id": note_id,
                        "title": meta.get("title", "无标题"),
                        "created_at": str(meta.get("created_at", meta.get("create_time", ""))),
                        "views": meta.get("views", 0),
                        # 如果正文太长，只截取前 400 个字符给大模型看，节省 Token 钱！
                        "snippet": (doc.page_content[:400] + "..." if len(doc.page_content) > 400 else doc.page_content),
                    })

            return json.dumps({"scope": scope, "notes": notes, "total": len(notes)}, ensure_ascii=False)
        except Exception as e:
            return json.dumps({"error": f"检索失败：{str(e)}"}, ensure_ascii=False)

    @tool
    async def get_all_titles(
            scope: str = "global",
            sort_by: str = "newest",
            config: RunnableConfig = None
    ) -> str:
        #大模型直接读 MySQL 因为向量检索（RAG）只适合查文本，如果用户问“点赞最多的是哪篇”，向量是查不出来的，必须用 SQL 查。
        """
        获取笔记的目录列表（包含所属话题）。
        :param scope: "global" 表示全站笔记；"personal" 表示当前用户。
        :param sort_by: 排序方式。可选 "newest"(最新), "likes"(点赞最多), "views"(浏览最多)。
        """
        user_id = config.get("configurable", {}).get("user_id")
        try:
            async with _ASYNC_ENGINE.connect() as conn:
                if sort_by == "likes":
                    order_clause = "ORDER BY n.likes DESC, n.create_time DESC"
                elif sort_by == "views":
                    order_clause = "ORDER BY n.views DESC, n.create_time DESC"
                else:
                    order_clause = "ORDER BY n.create_time DESC"

                # 🚀 新增：联表查出 topic 的名字  (LEFT JOIN)
                if scope == "personal":
                    sql = f"""SELECT n.id, n.title, n.create_time AS created_at, n.likes AS like_count, n.views, n.comment_count, t.name AS topic_name
                                  FROM note n 
                                  LEFT JOIN topic t ON n.topic_id = t.id
                                  WHERE n.is_deleted = 0 AND n.user_id = :uid {order_clause} LIMIT 10"""
                    params = {"uid": user_id}
                else:
                    sql = f"""SELECT n.id, n.title, n.create_time AS created_at, n.likes AS like_count, n.views, n.comment_count, t.name AS topic_name
                                  FROM note n 
                                  LEFT JOIN topic t ON n.topic_id = t.id
                                  WHERE n.is_deleted = 0 {order_clause} LIMIT 10"""
                    params = {}

                rows = (await conn.execute(text(sql), params)).fetchall()

            notes = [
                {
                    "note_id": row[0],
                    "title": row[1],
                    "created_at": str(row[2]),
                    "like_count": row[3],
                    "views": row[4],
                    "topic": row[6] or "未分类话题"  # 🚀 把话题暴露给大模型
                }
                for row in rows
            ]
            return json.dumps({"scope": scope, "sort_by": sort_by, "notes": notes}, ensure_ascii=False)
        except Exception as e:
            return json.dumps({"error": f"获取标题失败：{str(e)}"}, ensure_ascii=False)

    @tool
    async def get_note(note_id: str, config: RunnableConfig = None) -> str:
        """按笔记 ID 从数据库读取全站任意一篇完整笔记内容及它所属的话题。"""
        try:
            async with _ASYNC_ENGINE.connect() as conn:
                # 🚀 新增：联表查出 topic 的名字
                result = await conn.execute(
                    text("""SELECT n.title, n.summary, n.content, n.create_time, n.views, u.name AS author_name, t.name AS topic_name
                            FROM note n 
                            LEFT JOIN user u ON n.user_id = u.id 
                            LEFT JOIN topic t ON n.topic_id = t.id
                            WHERE n.id = :id AND n.is_deleted = 0"""),
                    {"id": note_id},
                )
                row = result.fetchone()

            if not row:
                return json.dumps({"error": f"笔记不存在"}, ensure_ascii=False)
            return json.dumps({"title": row[0], "content": row[2], "author": row[5], "topic": row[6] or "未分类话题"}, ensure_ascii=False)
        except Exception as e:
            return json.dumps({"error": f"读取失败：{str(e)}"}, ensure_ascii=False)

    @tool
    async def get_note_stats(config: RunnableConfig = None) -> str:
        """获取整个 LabFlow 平台的大盘统计数据。这段代码的目的是一口气查出平台的总笔记数、最新发布时间、本月新增量等等。"""
        try:
            async with _ASYNC_ENGINE.connect() as conn:
                row = (await conn.execute(
                    text("""
                         SELECT COUNT(*) AS total,  --【数个数】：统计 note 表里共有多少行（多少篇笔记）
                                MAX(create_time) AS latest, -- 【找最大值】：找所有笔记里，创建时间最晚的那一个
                             -- 【骚操作：条件求和】：
                            -- CURDATE() 是今天，DATE_SUB(..., INTERVAL 1 MONTH) 是一个月前。
                            -- (create_time >= ...) 会得到 True(1) 或 False(0)。
                            -- SUM 把这些 1 和 0 加起来，就神奇地算出了“最近一个月发了多少篇”。
                                SUM(create_time >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH)) AS this_month,
                                SUM(views) AS total_views, -- 【求和】：所有笔记的浏览量相加
                                SUM(likes) AS total_likes,
                                SUM(comment_count) AS total_comments
                         FROM note
                         WHERE is_deleted = 0
                         """)
                )).fetchone()  #.fetchone() 表示这条 SQL 只会返回一行数据，长这样：(150, datetime(...), 30, 5000, 200, 50)

            #数据封装
            return json.dumps(
                {
                    "total": int(row[0] or 0),
                    "latest": str(row[1]) if row[1] else None,
                    "this_month": int(row[2] or 0),
                    "total_views": int(row[3] or 0),
                    "total_likes": int(row[4] or 0),
                    "total_comments": int(row[5] or 0)
                },
                ensure_ascii=False,
            )
        except Exception as e:
            return json.dumps({"error": f"大盘统计失败：{str(e)}"}, ensure_ascii=False)

    @tool
    async def get_topic_stats(
        sort_by: str = "notes",
        limit: int = 15,
        config: RunnableConfig = None
    ) -> str:
        """
        获取各话题(Topic)的宏观统计数据。用于回答“哪个话题最火”、“哪个话题下笔记最多/评论最多/点赞最多”。
        :param sort_by: 排序维度。可选 "notes"(笔记数量最多), "comments"(评论最多), "likes"(点赞最多), "views"(浏览最多)。
        """
        try:
            async with _ASYNC_ENGINE.connect() as conn:
                if sort_by == "likes":
                    order_clause = "total_likes DESC"
                elif sort_by == "views":
                    order_clause = "total_views DESC"
                elif sort_by == "comments":
                    order_clause = "total_comments DESC"
                else:
                    order_clause = "note_count DESC" # 默认按笔记数量排序

                # 🚀 极其强大的 SQL：基于话题聚合统计所有笔记的热度！
                sql = f"""
                    SELECT t.name, 
                           COUNT(n.id) AS note_count, -- 统计这个话题下有多少篇笔记
                           -- 【防空洞：COALESCE】：如果一个话题刚建，里面一篇笔记都没有，SUM() 算出来是 NULL（空）。
                            -- COALESCE(SUM(...), 0) 的意思是：如果是 NULL，就把它变成数字 0。
                           COALESCE(SUM(n.views), 0) AS total_views,
                           COALESCE(SUM(n.likes), 0) AS total_likes,
                           COALESCE(SUM(n.comment_count), 0) AS total_comments
                    FROM topic t
                    LEFT JOIN note n ON t.id = n.topic_id AND n.is_deleted = 0
                    WHERE t.is_deleted = 0
                    GROUP BY t.id, t.name
                    ORDER BY {order_clause}
                    LIMIT :limit
                """
                rows = (await conn.execute(text(sql), {"limit": limit})).fetchall()

            topics = [
                {
                    "topic_name": row[0],
                    "note_count": int(row[1]),
                    "total_views": int(row[2]),
                    "total_likes": int(row[3]),
                    "total_comments": int(row[4])
                }
                for row in rows  # 列表推导式：遍历每一行，塞进字典里
            ]
            # 转成大模型爱看的 JSON
            return json.dumps({
                "sort_by": sort_by,
                "topic_stats": topics
            }, ensure_ascii=False)

        except Exception as e:
            return json.dumps({"error": f"获取话题统计失败：{str(e)}"}, ensure_ascii=False)

    @tool
    async def get_comments_analysis(
        scope: str = "all",
        note_id: str = None,
        topic: str = None,
        sort_by: str = "newest",
        limit: int = 30,
        config: RunnableConfig = None
    ) -> str:
        """
        获取真实的用户评论数据供大模型分析舆情。
        :param scope: 查询范围。"all"(全站最新评论), "note"(某篇笔记下的评论), "topic"(某话题笔记下的评论)。
        :param note_id: scope="note" 时传入。
        :param topic: scope="topic" 时传入，按笔记所属话题名称模糊匹配。
        :param sort_by: 排序方式。"newest"(最新), "likes"(点赞最多)。
        """
        try:
            async with _ASYNC_ENGINE.connect() as conn:
                # 🚀 连上 topic 表！去掉了 c.is_deleted
                base_sql = """
                    SELECT c.content AS comment_text, u.name AS reviewer_name, n.title AS note_title, c.create_time, c.likes, t.name AS topic_name
                    FROM comment c
                    LEFT JOIN user u ON c.user_id = u.id
                    LEFT JOIN note n ON c.note_id = n.id
                    LEFT JOIN topic t ON n.topic_id = t.id
                    WHERE n.is_deleted = 0
                """
                params = {"limit": limit}

                if scope == "note" and note_id:
                    base_sql += " AND c.note_id = :nid"
                    params["nid"] = note_id
                elif scope == "topic" and topic:
                    base_sql += " AND t.name LIKE :topic" # 🚀 精确匹配话题名
                    params["topic"] = f"%{topic}%"

                if sort_by == "likes":
                    base_sql += " ORDER BY c.likes DESC, c.create_time DESC LIMIT :limit"
                else:
                    base_sql += " ORDER BY c.create_time DESC LIMIT :limit"

                rows = (await conn.execute(text(base_sql), params)).fetchall()

            comments = [
                {
                    "topic_name": row[5] or "未分类话题",
                    "note_title": row[2] or "未知笔记",
                    "reviewer": row[1] or "匿名用户",
                    "content": row[0],
                    "time": str(row[3]),
                    "likes": row[4] or 0
                }
                for row in rows
            ]

            if not comments:
                return json.dumps({"message": "暂未找到相关评论数据"}, ensure_ascii=False)

            return json.dumps({
                "scope": scope,
                "target": note_id if scope == "note" else (topic if scope == "topic" else "全站"),
                "sort_by": sort_by,
                "comments_data": comments,
                "total_fetched": len(comments)
            }, ensure_ascii=False)

        except Exception as e:
            return json.dumps({"error": f"获取评论失败：{str(e)}"}, ensure_ascii=False)

    # 🚀 聚合所有工具，返回给 LangGraph
    tools = [
        get_current_user_info,
        search_notes,
        get_all_titles,
        get_note,
        get_note_stats,
        get_topic_stats,        # 🚀 核心新工具：话题宏观统计
        get_comments_analysis   # 🚀 升级版：带话题字段的评论分析
    ]
    tools_by_name = {t.name: t for t in tools} # 生成一个以工具名字为 key 的字典方便后续查找

    return tools, tools_by_name