"""
app/main.py (终极合并版：Redis 记忆 + 完美 流式通信SSE 流式输出)
uvicorn app.main:app --port 8001 启动
python -m app.worker 监听mq
改动说明：
  1. 引入 aio_pika 实现异步发送 RabbitMQ 消息，完全不阻塞流式输出！
  2. 彻底重构 /ai/chat/history 接口，改为直接从 MySQL 读取历史，与 Redis 彻底解耦。
"""
import asyncio
import os
import logging
import aio_pika
import json
from contextlib import asynccontextmanager

from dotenv import load_dotenv
load_dotenv()
from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from langchain_core.messages import HumanMessage, SystemMessage

# AsyncRedisSaver: LangGraph 官方提供的工具，专门把大模型的记忆状态序列化存进 Redis。
from langgraph.checkpoint.redis.aio import AsyncRedisSaver

from app.auth import get_request_user_id,get_redis
from app.core.graph import build_graph
from app.core.prompts import ROVER_SYSTEM_PROMPT
from app.core.schemas import ChatRequest
from app.storage.embeddings import _get_model
from app.storage.sync import get_vectorstore
from sqlalchemy import text # 🚀 引入 SQL 工具
from app.core.tools import _ASYNC_ENGINE # 🚀 引入数据库连接
"""
Redis 里面到底存了什么,存的是状态 定义在state.py中，当你和 AI 聊了几个回合后，这个被存进 Redis 的“状态包裹”，拆开来看长这样：
{
  "user_id": "5",
  "llm_calls": 3,
  "summary": "",
  "messages": [
    {"type": "system", "content": "你是 LabFlow 的 AI 助手...【用户专属长期记忆】..."},
    {"type": "human", "content": "帮我查一下 Spring Boot 的笔记"},
    {"type": "ai", "content": "好的，我这就去查", "tool_calls": [{"name": "search_notes", "args": {"query": "Spring Boot"}}]},
    {"type": "tool", "name": "search_notes", "content": "[笔记1, 笔记2...]"},
    {"type": "ai", "content": "为您找到以下笔记：..."}
  ]
}
当 LangGraph 决定把状态存进 Redis 时，它不是只存一个数字或一个标记，它是把上面那一整坨包含所有聊天记录的字典，压缩成二进制，一把塞进了 Redis 里
MySQL 里的是“净记录”：你只把最高度提炼的 human_msg（用户说的纯文本）和 full_ai_response（AI 经过思考后，吐出的优美 Markdown 回答）存了进去。即mysql中存的是用户的提问和ai的回答
"""


# ── 异步向 RabbitMQ 发送消息的工具函数  aio_pika 是 RabbitMQ 的异步驱动，完美契合 FastAPI 的高性能要求─────────────────────────
async def publish_chat_history(user_id: str, session_id: str, human_msg: str, ai_msg: str):
    try:
        # 建立与 RabbitMQ 的异步连接
        connection = await aio_pika.connect_robust(
            os.getenv("RABBITMQ_URL", "amqp://guest:guest@127.0.0.1/")
        )
        async with connection:
            #开启信道并声明一个“持久化”队列（durable=True 保证 MQ 重启数据不丢）
            channel = await connection.channel()
            queue = await channel.declare_queue("chat_history_queue", durable=True)

            # 把要发的数据组装成一个 Python 字典
            payload = {
                "user_id": user_id,
                "session_id": session_id,
                "human_msg": human_msg,
                "ai_msg": ai_msg
            }

            # 发送消息到默认交换机
            await channel.default_exchange.publish(
                aio_pika.Message(body=json.dumps(payload).encode()), #dumps字典转字符串，encode编码为字节流
                routing_key="chat_history_queue",
            )
    except Exception as e:
        print(f"[MQ Error] 发送聊天记录失败: {e}")

# ── 启动 / 关闭生命周期  状态持久化引擎。Redis 在这里不只是缓存，它存的是 AI 思考到一半的“快照”，支持断点续传。─────────────────────────────
@asynccontextmanager #一个装饰器，用来定义服务器启动和关闭时要做的事情。 服务器启动时会运行 yield 前面的代码，关闭时运行 yield 后面的代码。
async def lifespan(app: FastAPI):
    print("🚀 启动 AI 服务 (分布式高并发版)...")
    _get_model()   # 预热 bge-m3模型 防止第一次聊天时卡顿

    # 🚀 1. 动态构建 Redis 的 URL 连接字符串 (兼容你 Linux 虚拟机的密码)
    redis_host = os.getenv("REDIS_HOST", "127.0.0.1")
    redis_port = os.getenv("REDIS_PORT", "6379")
    redis_password = os.getenv("REDIS_PASSWORD")

    if redis_password:
        redis_url = f"redis://:{redis_password}@{redis_host}:{redis_port}/0"
    else:
        redis_url = f"redis://{redis_host}:{redis_port}/0"

    # 🚀 核心：AsyncRedisSaver 是 LangGraph 的“大脑存档器”
    #用刚刚拼好的链接去连 Redis，并生成一个 checkpointer（记忆存档器）
    async with AsyncRedisSaver.from_conn_string(redis_url) as checkpointer:
        # 把它作为 checkpointer 传给了你的 graph  自定义一个存储变量名为graph，存这个图
        app.state.graph = build_graph(get_vectorstore(), checkpointer)
        print("✅ AI 服务就绪 (已挂载 Redis 记忆引擎)")
        yield

    print("🛑 AI 服务关闭")

app = FastAPI(title="Labflow AI助手", lifespan=lifespan) #初始化服务器与生命周期

#CORS 跨域中间件
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── 主问答接口 SSE 流式对话 ────────────────────────────────
@app.post("/ai/chat")
async def chat(
    req: ChatRequest,
    user_id: str = Depends(get_request_user_id), #拦截请求，去 Token 里解析出用户的 ID，拿不到就报错拦住。
):
    graph = app.state.graph #从全局变量里把造好的 LangGraph 智能体取出来
    # 🚀 构造 Config，thread_id 是 Redis 里的 Key，用来区分不同用户的不同聊天窗口
    config = {
        "configurable": {
            "thread_id": f"{user_id}:{req.session_id}",
            "user_id": user_id,
        }
    }

    async def generate():
        full_ai_response = ""  # 🚀 缓冲池：用来拼接 AI 的完整回答，准备发给 MQ
        try:
            # 检查当前 thread 是否已有历史消息 (完美兼容 Redis 架构)
            saved = await graph.aget_state(config) #去 Redis 里查一下这个 thread_id 以前有没有聊过
            existing_messages = (
                saved.values.get("messages", []) if saved.values else []
            )

            # 只有第一次对话（没有历史）才注入 SystemMessage
            #如果没聊过（是个新窗口），就把“你是 Labflow 助手”这段系统设定（SystemMessage）塞在第一句。
            # 如果有历史记录，直接把用户说的话传进去就行，因为 Redis 里已经记住了前面的设定。
            if not existing_messages:
                input_messages = [
                    SystemMessage(content=ROVER_SYSTEM_PROMPT),
                    HumanMessage(content=req.question),
                ]
            else: #只要把用户新说的这一句话，追加进去就行了
                input_messages = [HumanMessage(content=req.question)]
            # 🚀 astream 是 LangGraph 的流式输出方法 graph.astream: 让大模型开始思考并流式输出。chunk 就是它吐出来的一个个包裹。
            async for chunk in graph.astream(
                input={
                    "messages": input_messages,
                    "user_id": user_id,
                    "llm_calls": 0,
                },
                config=config,
                stream_mode=["messages", "updates"],
                version="v2",
            ):
                # ── updates：用于向前端发送 [DEBUG] 标签，显示 AI 正在调哪个工具 ──────
                if chunk["type"] == "updates":
                    for node_name, update in chunk["data"].items():
                        if node_name == "llm_call":
                            calls = update.get("llm_calls", "?")
                            yield f"data: [DEBUG:llm_call:{calls}]\n\n"

                            msgs = update.get("messages", [])
                            for m in msgs:
                                for tc in getattr(m, "tool_calls", []):
                                    tool_name = tc.get("name", "tool")
                                    yield f"data: [DEBUG:tool:{tool_name}]\n\n"

                # ── messages：LLM token 流式输出 messages：真正的 AI 说话内容──────────────
                elif chunk["type"] == "messages":
                    msg, metadata = chunk["data"]

                    is_ai_text = (
                        metadata.get("langgraph_node") == "llm_call"
                        and msg.content
                        and not getattr(msg, "tool_call_chunks", None)
                    )

                    if is_ai_text:
                        # 换行符转义，防止 SSE 协议把 \n 当分隔符处理
                        # content = msg.content.replace("\n", "\\n")
                        # yield f"data: {content}\n\n"
                        msg_text = msg.content
                        full_ai_response += msg_text  # 🚀 拼接到缓冲池
                        yield f"data: {msg_text.replace('\n', '\\n')}\n\n" # 发送 SSE 格式数据
            # 🚀 核心：大模型输出完毕后，开一个后台线程异步把聊天记录抛给 MQ！完全不影响前端体验
            if full_ai_response:
                #asyncio.create_task 意味着 AI 回答完后，立即向前端发送结束信号，而投递 MQ 的动作在后台悄悄进行，不占用用户的等待时间。
                asyncio.create_task(publish_chat_history(
                    user_id=user_id,
                    session_id=req.session_id,
                    human_msg=req.question,
                    ai_msg=full_ai_response
                ))

        except Exception as e:
            yield f"data: [ERROR] {str(e)}\n\n"
            print(f"Graph 执行错误: {e}")

    return StreamingResponse(generate(), media_type="text/event-stream") #通过 StreamingResponse 保持长连接。


@app.get("/ai/chat/history")
async def get_chat_history(
        session_id: str,
        user_id: str = Depends(get_request_user_id)
):
    """提取指定会话的历史记录（已合并 AI 的多步思考过程）"""
    """🚀 彻底重构：从 MySQL 读取永久聊天记录，不再依赖易过期的 Redis  冷热分离。Redis 只存正在聊天的活跃数据（热），MySQL 存已经聊完的历史数据（冷）。"""
    try:
        async with _ASYNC_ENGINE.connect() as conn:
            # 按照时间顺序把这个人的这个 session 下的聊天全部查出来
            rows = (await conn.execute(
                text("""
                     SELECT role, content
                     FROM chat_message
                     WHERE session_id = :sid
                       AND user_id = :uid
                     ORDER BY create_time ASC
                     """),
                {"sid": session_id, "uid": user_id}
            )).fetchall()

        history = [
            {"role": row[0], "content": row[1]}
            for row in rows
        ]
        return {"code": 1, "data": history}

    except Exception as e:
        print(f"获取 MySQL 历史记录异常: {e}")
        return {"code": 1, "data": []}
    # graph = app.state.graph
    # config = {"configurable": {"thread_id": f"{user_id}:{session_id}"}}
    #
    # try:
    #     state = await graph.aget_state(config)
    #
    #     if not state.values or "messages" not in state.values:
    #         return {"code": 1, "data": []}
    #
    #     history = []
    #     current_ai_content = ""  # 🚀 缓冲池：用来拼接同一回合内的多段 AI 发言
    #
    #     for msg in state.values["messages"]:
    #         if msg.type == "human":
    #             # 🚀 遇到人类提问时，如果之前有积攒的 AI 发言，先把它打包推入历史记录
    #             if current_ai_content:
    #                 history.append({
    #                     "role": "ai",
    #                     "content": current_ai_content.strip()
    #                 })
    #                 current_ai_content = ""  # 清空缓冲池
    #
    #             # 推入人类的发言
    #             history.append({
    #                 "role": "user",
    #                 "content": msg.content
    #             })
    #
    #         elif msg.type == "ai" and msg.content:
    #             # 🚀 遇到 AI 发言时，不要立刻推入历史记录，而是拼接到缓冲池中
    #             # 用两个换行符隔开它的思考步骤，排版更好看
    #             if current_ai_content:
    #                 current_ai_content += "\n\n" + msg.content
    #             else:
    #                 current_ai_content = msg.content
    #
    #     # 🚀 循环结束后，别忘了把最后一段积攒的 AI 发言推入历史记录
    #     if current_ai_content:
    #         history.append({
    #             "role": "ai",
    #             "content": current_ai_content.strip()
    #         })
    #
    #     return {"code": 1, "data": history}
    #
    # except Exception as e:
    #     print(f"获取历史记录异常: {e}")
    #     return {"code": 1, "data": []}


@app.delete("/ai/chat/history")
async def delete_chat_history(
        session_id: str,
        user_id: str = Depends(get_request_user_id)
):
    """删除指定的聊天会话（同步清理 MySQL 历史与 Redis 底层记忆）"""

    # ── 1. 清理 MySQL 中的可见聊天记录 (冷数据) ──
    try:
        # 使用 .begin() 开启事务，执行写操作
        async with _ASYNC_ENGINE.begin() as conn:
            await conn.execute(
                text("DELETE FROM chat_message WHERE session_id = :sid AND user_id = :uid"),
                {"sid": session_id, "uid": user_id}
            )
    except Exception as e:
        print(f"MySQL 删除历史记录异常: {e}")
        return {"code": 0, "msg": "数据库删除失败"}

    # ── 2. 清理 Redis 中的 LangGraph 底层图状态 (热数据) ──
    try:
        redis = get_redis()
        # 严格按照 graph.py 中的规则拼接 thread_id
        thread_id = f"{user_id}:{session_id}"

        # LangGraph 会生成以 checkpoint_latest, checkpoint, checkpoint_writes 等开头的 key
        # 我们使用异步 scan 游标迭代，安全地找出所有包含该 thread_id 的 key，防止阻塞 Redis
        cursor = 0
        keys_to_delete = []
        while True:
            # 模糊匹配带有 thread_id 的所有 key 亮点：使用异步 scan 游标迭代，而不是 keys *，防止在大并发下阻塞 Redis
            cursor, keys = await redis.scan(cursor, match=f"*{thread_id}*", count=100)
            keys_to_delete.extend(keys)
            if cursor == 0:
                break

        # 如果找到了相关垃圾数据，批量删除！
        if keys_to_delete:
            await redis.delete(*keys_to_delete)
            print(f"🗑️ [Redis] 成功清理底层会话状态，释放 Key 数量: {len(keys_to_delete)}")

    except Exception as e:
        # Redis 清理失败不阻断流程，因为只要 MySQL 删了前端就不会展示了
        # Redis 里的残留数据对用户不可见，属于可容忍的脏数据
        print(f"Redis 状态清理异常: {e}")

    return {"code": 1, "msg": "删除成功"}


@app.get("/ai/health")
async def health():
    # 检测服务是否正常存活
    return {"status": "ok", "engine": "redis_memory_enabled"}