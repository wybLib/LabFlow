import os
from typing import Literal
import aio_pika
import json

from dotenv import load_dotenv
from langchain_core.messages import SystemMessage, RemoveMessage
# from langchain_openai import ChatOpenAI
from langgraph.graph import END, START, StateGraph
from langgraph.prebuilt import ToolNode
from langgraph.store.memory import InMemoryStore
from sqlalchemy import text # 🚀 引入 SQL 工具

from app.core.schemas import AgentContext
from app.core.state import NoteAgentState
from app.core.tools import make_tools, _ASYNC_ENGINE # 🚀 复用 tools.py 里的数据库连接池
from app.auth import get_redis
from app.core.prompts import UserFacts, memory_prompt
from langchain_deepseek import ChatDeepSeek

load_dotenv()  # 加载 .env 文件里的环境变量

_store = InMemoryStore() # 内存存储，LangGraph 原生自带的组件，暂作占位用


def build_graph(vectorstore, checkpointer):

    # ── 初始化模型 ────────────────────────────────────────────────
    # model = ChatOpenAI(
    #     model=os.getenv("DEEPSEEK_MODEL"),
    #     api_key=os.getenv("DEEPSEEK_API_KEY"),
    #     base_url=os.getenv("DEEPSEEK_BASE_URL"),
    # )
    #
    # deepseek_model = ChatOpenAI(
    #     model=os.getenv("DEEPSEEK_MODEL"),
    #     api_key=os.getenv("DEEPSEEK_API_KEY"),
    #     base_url=os.getenv("DEEPSEEK_BASE_URL"),
    # )
    model = ChatDeepSeek(  # 初始化第一个 AI 员工：负责正常的聊天和调用工具
        model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        api_base=os.getenv("DEEPSEEK_BASE_URL"),
    )

    deepseek_model = ChatDeepSeek(  # 初始化第二个 AI 员工：专门负责“提取记忆”和“压缩摘要”
        model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        api_base=os.getenv("DEEPSEEK_BASE_URL"),
        temperature=0.1,  # 降低发散，确保精准提取
    )

    # 从你写的 tools.py 里拿到工具箱，然后把它“绑定”给第一个 AI 员工
    tools, _ = make_tools(vectorstore)
    model_with_tools = model.bind_tools(tools)

    # ── 节点一：llm_call (AI 思考与读取记忆) ──────────────────────────────────
    # 🚀 删除了报错的 config 参数，直接从 state 中读取 user_id
    #async 和 await 是 Python 处理高并发的利器。因为去 Redis 取数据、等大模型回话都需要时间，用异步可以保证在这个等待期间，你的服务器还能去服务其他用户，不会卡死。
    async def llm_call(state: NoteAgentState):
        user_id = state.get("user_id") # 1. 从“包裹（state）”里拿出当前用户的 ID
        # redis = get_redis() # 连上 Redis 数据库
        user_memory = None

        # 跨会话读取用户的长期档案
        # ltm_key = f"user:ltm:{user_id}"
        # user_memory = await redis.get(ltm_key)
        #从mysql中读取长期记忆
        try:
            async with _ASYNC_ENGINE.connect() as conn:
                row = (await conn.execute(
                    text("SELECT persona_text FROM user_persona WHERE user_id = :uid"),
                    {"uid": user_id}
                )).fetchone()
                if row and row[0]:
                    user_memory = row[0]
        except Exception as e:
            print(f"[DB Error] 读取长期记忆失败: {e}")

        messages = list(state["messages"]) # 拿到当前的聊天记录

        # 🚀 3. 核心修复逻辑：动态重构系统提示词
        if messages and isinstance(messages[0], SystemMessage):
            # A. 提取基础 Prompt：把旧记忆区域全部切掉，只留下最原始的设定
            # 无论之前拼了多少次，我们只保留 ROVER_SYSTEM_PROMPT 的部分
            base_content = messages[0].content.split("【用户专属长期记忆")[0].strip()

            # B. 注入最新记忆：如果 MySQL 里有东西，就拼上最新的
            if user_memory:
                new_content = base_content + f"\n\n【用户专属长期记忆（全局画像）】\n{user_memory}"
            else:
                new_content = base_content

            # C. 覆盖替换：用全新的内容替换掉第一条系统消息
            messages[0] = SystemMessage(content=new_content, id=messages[0].id)

        # 4. 让带着工具、看过了长期记忆的 AI 开始思考并作答
        response = await model_with_tools.ainvoke(messages)  #ainvoke和invoke a是异步函数 不阻塞线程  invoke是同步函数 会阻塞当前线程
        # 5. 把 AI 的回答塞回“包裹（state）”里，顺便把思考次数 + 1
        return {
            "messages": [response],
            "llm_calls": state.get("llm_calls", 0) + 1,
        }

    # ── 条件边：should_continue ───────────────────────────────────
    # 有 tool_calls → 继续调工具；没有 → 结束 LLM 循环，去提取长期记忆。 当 AI 思考完（llm_call 结束）后，包裹来到这里，决定下一步去哪。
    def should_continue(state: NoteAgentState) -> Literal["tool_node", "update_ltm"]:
        last = state["messages"][-1] # 看看 AI 刚刚说了什么
        if last.tool_calls: # 如果 AI 说：“我要用工具！”（tool_calls 有值）
            return "tool_node"
        return "update_ltm" # 如果 AI 只是普普通通说了一段话（没有用工具） 转向：当前回合聊天结束，去“提取记忆工作站”

    # ── 节点二：update_ltm (写记忆) ───────────────────────────────
    # 🚀 同样删除 config 参数，直接从 state 拿 user_id 分析用户刚才说了啥，有没有值得长期记住的偏好
    async def update_ltm(state: NoteAgentState):
        user_id = state.get("user_id")
        messages = state["messages"]
        # redis = get_redis()
        # ltm_key = f"user:ltm:{user_id}"

        # 1. 倒序查找，找到用户最近说的一句话（HumanMessage）
        last_human_msg = next((m for m in reversed(messages) if m.type == "human"), None)
        if not last_human_msg:
            return {} # 没找到就算了

        # 2. 去 Redis 里查一下，以前有没有存过这个用户的记忆？
        # existing_facts = await redis.get(ltm_key) or "暂无记录"
        existing_facts = "暂无记录"
        #改动：使用mysql
        #先查mysql原有记忆
        try:
            async with _ASYNC_ENGINE.connect() as conn:
                row = (await conn.execute(
                    text("SELECT persona_text FROM user_persona WHERE user_id = :uid"),
                    {"uid": user_id}
                )).fetchone()
                if row and row[0]:
                    existing_facts = row[0]
        except Exception:
            pass


        # 强制大模型输出 JSON 结构数组
        extractor_llm = deepseek_model.with_structured_output(UserFacts)
        extraction_chain = memory_prompt | extractor_llm # 将提示词和模型组装成一条“处理链（Chain）”

        try:
            # 4. 让大模型根据旧记忆和用户新说的话，提取新事实
            result = await extraction_chain.ainvoke({
                "existing_facts": existing_facts,
                "user_input": last_human_msg.content
            })

            # 如果大模型提取到了新东西，把它拼接到旧记忆下面，存回 Redis  改动：持久化到mysql  mq发消息给java后端消费
            if result and result.facts:
                # 🚀 核心修复：去重逻辑
                # 将旧事实按行切分，存入 set 提高查询效率
                existing_set = set([line.strip("- ").strip() for line in existing_facts.split("\n") if line.strip()])

                new_unique_facts = []
                for f in result.facts:
                    # 只有当这个事实不在旧记忆里时，才添加
                    if f.strip() not in existing_set:
                        new_unique_facts.append(f"- {f.strip()}")

                # 如果没有新事实，直接返回，不触发 MQ 和 数据库更新
                if not new_unique_facts:
                    return {}
                # 拼接新记忆（只包含不重复的部分）
                new_facts_combined = "\n".join(new_unique_facts)
                updated_memory = f"{existing_facts}\n{new_facts_combined}".replace("暂无记录\n", "").strip()
                # await redis.set(ltm_key, updated_memory)
                # async with _ASYNC_ENGINE.begin() as conn:
                #     # 使用 ON DUPLICATE KEY UPDATE 语法，有则更新，无则插入
                #     await conn.execute(
                #         text("""
                #              INSERT INTO user_persona (user_id, persona_text)
                #              VALUES (:uid, :text) ON DUPLICATE KEY
                #              UPDATE persona_text = :text
                #              """),
                #         {"uid": user_id, "text": updated_memory}
                #     )
                # 🚀 改造点：改为异步抛给 RabbitMQ
                try:
                    connection = await aio_pika.connect_robust(
                        os.getenv("RABBITMQ_URL", "amqp://guest:guest@127.0.0.1/"))
                    async with connection:
                        channel = await connection.channel()

                        # 组装消息体
                        payload = {
                            "user_id": user_id,
                            "persona_text": updated_memory
                        }

                        # 发送给 Java 端的 user_persona_queue 队列
                        await channel.default_exchange.publish(
                            aio_pika.Message(body=json.dumps(payload).encode()),
                            routing_key="user_persona_queue",
                        )
                    print(f"[LTM] 🧠 记忆生长已投递MQ！用户 {user_id} 新增记忆:\n{new_facts_combined}")
                except Exception as mq_e:
                    print(f"[MQ Error] LTM 投递失败: {mq_e}")
        except Exception as e:
            print(f"[LTM Error] 记忆提取失败: {e}")

        return {}

    # ── 节点三：summarize_if_needed ───────────────────────────────
    #短期记忆压缩防爆 如果你们聊得太久了（消息太多），大模型的上限会爆掉（Token Limit），甚至按字数算钱会变贵。这个节点就是“瘦身管家”。
    async def summarize_if_needed(state: NoteAgentState):
        messages = state["messages"]
        if len(messages) <= 40: # 如果总消息少于 40 条，很安全，直接放行
            return {}

        # 超过 40 条了！开始滑动窗口操作：
        recent = messages[-20:] # 保留最近的 20 条 保证上下文连贯
        to_summarize = messages[:-20] # 前面那些旧账，准备拿去浓缩
        existing_summary = state.get("summary", "")

        summary_prompt = f"""
        你是对话历史压缩助手。请将以下对话压缩成简洁的 bullet 摘要，保留：
        - 用户的核心目标和待办事项
        - 已确认的关键事实（笔记 ID、标题、日期等等）
        已有摘要（如有）：
        {existing_summary}
        需要新增压缩的对话：
        """

        # 让 AI 把旧账浓缩成一段短摘要
        new_summary = await deepseek_model.ainvoke(
            [SystemMessage(content=summary_prompt)] + to_summarize
        )

        # 告诉框架：删掉这些旧消息 (RemoveMessage)
        delete_ops = [RemoveMessage(id=m.id) for m in to_summarize]
        # 做一条新的系统消息，里面装着浓缩后的摘要
        summary_msg = SystemMessage(
            content=f"【历史对话摘要】\n{new_summary.content}",
            id="summary-msg",
        )

        # 把“删除旧消息的操作” + “浓缩摘要” + “最近的 20 条消息”塞回包裹里
        return {
            "messages": delete_ops + [summary_msg] + recent,
            "summary": new_summary.content,
        }

    # ── 组装图 ────────────────────────────────────────────────────
    #声明这是一张基于 NoteAgentState 这个“包裹”流转的图
    agent_builder = StateGraph(
        NoteAgentState,
        context_schema=AgentContext,
    )

    # 2. 把之前写好的 4 个工作站放到车间里
    agent_builder.add_node("llm_call", llm_call)
    agent_builder.add_node("tool_node", ToolNode(tools))
    agent_builder.add_node("update_ltm", update_ltm)   # 🚀 新增节点
    agent_builder.add_node("summarize_if_needed", summarize_if_needed)

    # 3. 铺设传送带（连接边）
    agent_builder.add_edge(START, "llm_call") # 开始第一步肯定是让 AI 思考
    agent_builder.add_edge("tool_node", "llm_call") # 工具用完后，再让 AI 思考一下结果

    # 4. 设置红绿灯分流（条件边）
    # 从 llm_call 出来后，调用 should_continue 函数判断，决定是去用工具，还是去提记忆
    agent_builder.add_conditional_edges(
        "llm_call",
        should_continue,
        ["tool_node", "update_ltm"],  # 🚀 分流到提取记忆
    )

    # 提完长期记忆后，去判断要不要压缩短期记忆
    agent_builder.add_edge("update_ltm", "summarize_if_needed") # 🚀 记忆提完，走原有的压缩流程
    # 压缩完，流程结束！
    agent_builder.add_edge("summarize_if_needed", END)

    # 5. 编译成可运行的程序，接入 checkpointer (这就是你用的 Redis，用来保存当前会话进度的)
    return agent_builder.compile(
        checkpointer=checkpointer, # 🚀 在这里，Redis 被彻底“装进”了图的底层
        store=_store,
    )
