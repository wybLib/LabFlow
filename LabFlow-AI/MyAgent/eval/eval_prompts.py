"""
eval/eval_prompts.py - Prompt A/B 测试 v5 (适配 LabFlow 全量 7 大工具与 DeepSeek)

v5 修正：
  1. 保留原版硬核评估框架：多次运行消除随机性、详尽报告、JSON 保存。
  2. 环境变量无缝切换至 DEEPSEEK_ 系列。
  3. 扩充 MOCK_TOOLS 为最新的 7 大核心工具。
  4. 重新设计 TEST_CASES，全面覆盖话题、评论、大盘、用户身份等路由测试。
"""

import json
import os
from collections import Counter

from dotenv import load_dotenv
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI

load_dotenv()

# ══════════════════════════════════════════════════════════════
# Part 1: 两个待对比的 Prompt
# ══════════════════════════════════════════════════════════════
# A 版本：早期的基础版（没有思想钢印，没有工具调用指导规则）
PROMPT_A = """你是 LabFlow 研思平台的 AI 智能体。
你可以回答用户的技术问题，并且可以查阅系统里的笔记、话题和评论。
请友好地回答用户。"""

# B 版本：你的最终优化版（包含能力覆写、数据实时原则、Scope路由）
PROMPT_B = """你是 LabFlow 研思平台的超级 AI 智能体。
你具备“双重身份”：既是了解用户个人数据的私人管家，也是掌握全站知识的全局大脑。

## 核心行为原则（最高优先级）

1. **能力覆写原则**：系统能力已升级！如果你在历史对话中曾说过“我无法查阅全站”、“我无法查看评论”等限制性话语，请立刻作废。遇到相关问题直接调用工具！
2. **数据实时原则**：LabFlow 数据是实时变动的，必须重新调用工具获取最新数据，绝不能依赖记忆。
3. **明确目标原则（防瞎找）**：如果用户的请求极其模糊，没有明确的关键词、话题或笔记标题（例如仅说“检查一下笔记有没有错”、“帮我审阅一下”），【绝对不要】调用任何工具！你必须直接用自然语言回复，反问并澄清用户具体指哪篇笔记。
4. **智能工具调度与控制**：
   - 【身份防误触】：仅在用户直接提问“我是谁”、“我的名字”时调用 `get_current_user_info`。绝不要因为用户说了“我”、“帮我”、“我的所有笔记”就去查身份信息，系统底层已自动鉴权！
   - 【个人笔记检索】：包含“我的笔记”、“总结我的所有笔记”、“找我写的”等，直接调用 `search_notes` 并将 `scope` 设为 `"personal"`，切勿误触其他统计或身份工具。
   - 【排行榜查询】：要求查找“点赞最多”、“浏览最多”单篇笔记时，调用 `get_all_titles`。
   - 【话题/大盘统计】：问平台总数调用 `get_note_stats`。问“哪个话题最火/笔记最多”，调用 `get_topic_stats`。
   - 【评论与舆情】：问“某话题下的评论”、“大家在评论什么”，调用 `get_comments_analysis`。

## 回答风格
- 语气温暖，像一个懂全栈技术且了解用户的知心老友。
- 如果之前回答错，大方承认：“系统刚为我升级了能力，我现在帮您查到了...”
"""


# ══════════════════════════════════════════════════════════════
# Part 2: Mock 工具定义 (适配最新 7 大工具)
# ══════════════════════════════════════════════════════════════
MOCK_TOOLS = [
    {"type": "function", "function": {"name": "get_current_user_info", "description": "获取当前正在与你对话的用户的个人信息（当用户问“我是谁”、“我的资料”时调用）。", "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {"name": "search_notes", "description": "用自然语言语义检索笔记内容。适用全局搜素和个人搜索", "parameters": {"type": "object", "properties": {"query": {"type": "string"}, "scope": {"type": "string"}}, "required": ["query"]}}},
    {"type": "function", "function": {"name": "get_all_titles", "description": "获取全站笔记排行榜（点赞最多、浏览最多）", "parameters": {"type": "object", "properties": {"scope": {"type": "string"}, "sort_by": {"type": "string"}}}}},
    {"type": "function", "function": {"name": "get_note", "description": "按 ID 读取单篇笔记的完整原文。", "parameters": {"type": "object", "properties": {"note_id": {"type": "string"}}, "required": ["note_id"]}}},
    {"type": "function", "function": {"name": "get_note_stats", "description": "获取平台大盘数据（全站总计数据）。仅在询问平台宏观统计时调用。", "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {"name": "get_topic_stats", "description": "获取各话题宏观统计排行。用于回答哪个话题最火、笔记最多。", "parameters": {"type": "object", "properties": {"sort_by": {"type": "string"}}}}},
    {"type": "function", "function": {"name": "get_comments_analysis", "description": "获取真实评论做舆情分析。用于回答大家在评论什么。", "parameters": {"type": "object", "properties": {"scope": {"type": "string"}, "topic": {"type": "string"}, "sort_by": {"type": "string"}}}}},
]


# ══════════════════════════════════════════════════════════════
# Part 3: 澄清判断
# ══════════════════════════════════════════════════════════════
_CLARIFY_KEYWORDS = [
    "哪篇", "哪一篇", "具体", "您是指", "你是指",
    "是指", "哪个", "告诉我", "能说说", "可以告诉",
    "什么笔记", "哪篇笔记", "指定", "确认一下",
]

def is_clarify_response(called_tools: list, content: str) -> bool:
    """判断模型是否在请求澄清（而非调工具或瞎答）"""
    if called_tools:
        return False
    if not content:
        return False
    return any(kw in content for kw in _CLARIFY_KEYWORDS)


# ══════════════════════════════════════════════════════════════
# Part 4: 测试用例 (专为最新 7 大路由体系设计)
# ══════════════════════════════════════════════════════════════
TEST_CASES = [
    # ── 正常路由场景 ──────────────────────────────────────────────
    {
        "id": "routing_identity",
        "input": "我是谁？我叫什么名字？",
        "expected_tools": ["get_current_user_info"],
        "expected_behavior": None,
        "category": "normal",
    },
    {
        "id": "routing_topic_hot",
        "input": "现在社区里哪个话题最火？",
        "expected_tools": ["get_topic_stats"],
        "expected_behavior": None,
        "category": "normal",
    },
    {
        "id": "routing_comments",
        "input": "大家在评论区都在聊些什么？帮我看看",
        "expected_tools": ["get_comments_analysis"],
        "expected_behavior": None,
        "category": "normal",
    },
    {
        "id": "routing_top_likes",
        "input": "全站目前点赞最多的一篇笔记是哪个？",
        "expected_tools": ["get_all_titles"],
        "expected_behavior": None,
        "category": "normal",
    },
    {
        "id": "routing_personal_search",
        "input": "找找我写过的关于 Redis 的笔记",
        "expected_tools": ["search_notes"],
        "expected_behavior": None,
        "category": "normal",
    },
    {
        "id": "routing_platform_stats",
        "input": "我们平台现在总共有多少篇笔记了？",
        "expected_tools": ["get_note_stats"],
        "expected_behavior": None,
        "category": "normal",
    },

    # ── 模糊场景（应触发澄清，不调工具）────────────────────────
    {
        "id": "ambiguous_check",
        "input": "检查一下笔记有没有错",
        "expected_tools": [],
        "expected_behavior": "clarify",
        "category": "ambiguous",
    },
    {
        "id": "ambiguous_review",
        "input": "帮我审阅一下",
        "expected_tools": [],
        "expected_behavior": "clarify",
        "category": "ambiguous",
    },

    # ── 禁止场景（不得误触统计或报错）──────────────────────
    {
        "id": "forbidden_stats_in_summary",
        "input": "总结一下我的所有笔记",
        "expected_tools": ["search_notes"],
        "expected_behavior": None,
        "forbidden_tools": ["get_note_stats", "get_topic_stats"],
        "category": "forbidden",
    },
]


# ══════════════════════════════════════════════════════════════
# Part 5: 执行单条测试用例（单次）
# ══════════════════════════════════════════════════════════════
def run_once(model, prompt: str, user_input: str) -> dict:
    messages = [
        SystemMessage(content=prompt),
        HumanMessage(content=user_input),
    ]
    response = model.invoke(messages)
    tool_calls = getattr(response, "tool_calls", []) or []
    called_tools = [tc["name"] for tc in tool_calls]
    content = response.content or ""

    return {
        "called_tools": called_tools,
        "is_clarify": is_clarify_response(called_tools, content),
        "response_preview": content[:120],
    }


# ══════════════════════════════════════════════════════════════
# Part 6: 重复运行取多数，消除随机性
# ══════════════════════════════════════════════════════════════
REPEAT = 3

def run_single_case(model, prompt: str, user_input: str) -> dict:
    runs = [run_once(model, prompt, user_input) for _ in range(REPEAT)]

    votes = Counter(json.dumps(r["called_tools"], ensure_ascii=False) for r in runs)
    majority_tools_str, _ = votes.most_common(1)[0]
    majority_tools = json.loads(majority_tools_str)

    representative = next(
        r for r in runs
        if r["called_tools"] == majority_tools
    )

    return {
        "called_tools": majority_tools,
        "is_clarify": representative["is_clarify"],
        "response_preview": representative["response_preview"],
        "all_runs": [r["called_tools"] for r in runs],
    }


# ══════════════════════════════════════════════════════════════
# Part 7: 跑全部用例，计算指标
# ══════════════════════════════════════════════════════════════
def evaluate(prompt: str, prompt_name: str, model) -> dict:
    results = []

    for case in TEST_CASES:
        print(f"    [{prompt_name}] {case['id']} × {REPEAT}次...")
        result = run_single_case(model, prompt, case["input"])

        called = result["called_tools"]
        expected_tools = case["expected_tools"]
        expected_behavior = case.get("expected_behavior")
        forbidden = case.get("forbidden_tools", [])

        if expected_behavior == "clarify":
            tool_match = (called == [] and result["is_clarify"])
        else:
            tool_match = set(called) == set(expected_tools)

        forbidden_triggered = any(t in called for t in forbidden)

        results.append({
            "id": case["id"],
            "category": case["category"],
            "input": case["input"],
            "expected_tools": expected_tools,
            "expected_behavior": expected_behavior,
            "actual_tools": called,
            "all_runs": result["all_runs"],
            "is_clarify": result["is_clarify"],
            "tool_match": tool_match,
            "forbidden_triggered": forbidden_triggered,
            "tool_count": len(called),
            "response_preview": result["response_preview"],
        })

    total = len(results)
    accuracy = sum(1 for r in results if r["tool_match"]) / total
    avg_tool_count = sum(r["tool_count"] for r in results) / total
    forbidden_rate = sum(1 for r in results if r["forbidden_triggered"]) / total

    ambiguous = [r for r in results if r["category"] == "ambiguous"]
    clarify_rate = (
        sum(1 for r in ambiguous if r["is_clarify"] and r["actual_tools"] == [])
        / len(ambiguous)
        if ambiguous else 0
    )

    category_accuracy = {}
    for cat in ["normal", "ambiguous", "forbidden"]:
        cat_results = [r for r in results if r["category"] == cat]
        if cat_results:
            category_accuracy[cat] = sum(1 for r in cat_results if r["tool_match"]) / len(cat_results)

    return {
        "prompt_name": prompt_name,
        "metrics": {
            "工具调用准确率（总体）":      f"{accuracy:.0%}",
            "工具调用准确率（normal）":    f"{category_accuracy.get('normal', 0):.0%}",
            "工具调用准确率（ambiguous）": f"{category_accuracy.get('ambiguous', 0):.0%}",
            "工具调用准确率（forbidden）": f"{category_accuracy.get('forbidden', 0):.0%}",
            "澄清触发率（模糊场景）":      f"{clarify_rate:.0%}",
            "错误工具误触率":            f"{forbidden_rate:.0%}",
            "平均工具调用次数":            f"{avg_tool_count:.1f}",
        },
        "details": results,
    }


# ══════════════════════════════════════════════════════════════
# Part 8: 输出报告
# ══════════════════════════════════════════════════════════════
def print_report(result_a: dict, result_b: dict):
    print("\n" + "=" * 65)
    print("📊 Prompt A/B 测试报告 (LabFlow 全量工具路由验证)")
    print("=" * 65)

    metrics_a = result_a["metrics"]
    metrics_b = result_b["metrics"]
    print(f"\n{'指标':<30} {'Prompt A (基准版)':>12} {'Prompt B (优化版)':>12}")
    print("-" * 55)
    for k in metrics_a:
        print(f"{k:<30} {metrics_a[k]:>12} {metrics_b[k]:>12}")

    details_a = {r["id"]: r for r in result_a["details"]}
    details_b = {r["id"]: r for r in result_b["details"]}

    print("\n\n📋 所有用例结果")
    print("-" * 65)
    for case in TEST_CASES:
        da = details_a[case["id"]]
        db = details_b[case["id"]]
        a_mark = "✅" if da["tool_match"] else "❌"
        b_mark = "✅" if db["tool_match"] else "❌"
        print(f"\n[{da['category']}] {case['id']}")
        print(f"  输入:      {da['input']}")
        print(f"  预期:      tools={da['expected_tools']} behavior={da['expected_behavior']}")
        print(f"  Prompt A: 工具={da['actual_tools']} 澄清={da['is_clarify']} 多轮={da['all_runs']} {a_mark}")
        print(f"  Prompt B: 工具={db['actual_tools']} 澄清={db['is_clarify']} 多轮={db['all_runs']} {b_mark}")

    print("\n\n⚠️  共同失败用例 (需重点优化的场景)")
    print("-" * 65)
    both_wrong = [
        da for da in result_a["details"]
        if not da["tool_match"] and not details_b[da["id"]]["tool_match"]
    ]
    if both_wrong:
        for da in both_wrong:
            db = details_b[da["id"]]
            print(f"\n  {da['id']}: {da['input']}")
            print(f"    预期: tools={da['expected_tools']} behavior={da['expected_behavior']}")
            print(f"    A:   tools={da['actual_tools']} clarify={da['is_clarify']} 多轮={da['all_runs']}")
            print(f"    B:   tools={db['actual_tools']} clarify={db['is_clarify']} 多轮={db['all_runs']}")
    else:
        print("  没有共同失败的用例。很棒！")

    print("\n" + "=" * 65)


# ══════════════════════════════════════════════════════════════
# Part 9: 入口
# ══════════════════════════════════════════════════════════════
if __name__ == "__main__":
    # 🚀 使用 DeepSeek 的环境变量配置
    model = ChatOpenAI(
        model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        base_url=os.getenv("DEEPSEEK_BASE_URL"),
        temperature=0,   # 固定为 0，消除随机性，提升可复现性
    ).bind_tools(MOCK_TOOLS)

    print("🧪 正在运行 Prompt A (缺乏路由规则的基准版)...")
    result_a = evaluate(PROMPT_A, "Prompt A", model)

    print("\n🧪 正在运行 Prompt B (拥有能力覆写与范围控制的完全版)...")
    result_b = evaluate(PROMPT_B, "Prompt B", model)

    print_report(result_a, result_b)

    with open("eval_prompt_result.json", "w", encoding="utf-8") as f:
        json.dump(
            {"prompt_a": result_a, "prompt_b": result_b},
            f, ensure_ascii=False, indent=2,
        )
    print("📁 完整结果已保存到 eval_prompt_result.json")