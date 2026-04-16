# """
# eval/test.py - Ragas 0.3.9 光速四强版 (Recall, Precision, Relevancy, Similarity)
# """
#
# import os
# import json
# import warnings
# from tqdm import tqdm
# from dotenv import load_dotenv
#
# # 彻底静默警告
# warnings.filterwarnings("ignore", category=DeprecationWarning)
# warnings.filterwarnings("ignore", category=UserWarning)
#
# from langchain_chroma import Chroma
# from langchain_openai import ChatOpenAI
# from ragas.dataset_schema import SingleTurnSample, EvaluationDataset
# from ragas import evaluate
#
# # 🚀 导入真正适合 DeepSeek 的高效指标
# from ragas.metrics import (
#     LLMContextRecall,
#     ContextPrecision,
#     AnswerRelevancy,
#     SemanticSimilarity
# )
#
# from openai import OpenAI
# from ragas.llms import llm_factory
# from ragas.run_config import RunConfig
#
# from langchain_huggingface import HuggingFaceEmbeddings as LangchainHFEmbeddings
#
# from app.core.rag import make_rag_retriever, rerank_docs
# from app.core.prompts import ROVER_SYSTEM_PROMPT
# from app.storage.embeddings import BGEEmbeddings
#
# load_dotenv()
#
# def get_final_chroma_path():
#     current_file_path = os.path.abspath(__file__)
#     project_root = os.path.dirname(os.path.dirname(current_file_path))
#     return os.path.join(project_root, "chroma_db")
#
# def main():
#     print("🚀 正在初始化 LabFlow 评测管线 (光速四强版)...")
#
#     llm = ChatOpenAI(
#         model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
#         api_key=os.getenv("DEEPSEEK_API_KEY"),
#         base_url=os.getenv("DEEPSEEK_BASE_URL"),
#         max_tokens=8192,
#         temperature=0.1,
#     )
#
#     bge_emb = BGEEmbeddings()
#     vectorstore = Chroma(
#         persist_directory=get_final_chroma_path(),
#         embedding_function=bge_emb,
#         collection_name=os.getenv("CHROMA_COLLECTION", "black_note_all")
#     )
#
#     from langchain_core.prompts import ChatPromptTemplate
#     qa_prompt = ChatPromptTemplate.from_messages([
#         ("system", ROVER_SYSTEM_PROMPT + """
#
# 【相关背景资料】:
# {context}
#
# 请严格基于上述背景详尽地回答用户的问题。
# ⚠️ 输出要求：请提取最核心、最有价值的信息，总字数请控制在 200-350 字左右，保持语义连贯完整。"""),
#         ("user", "{query}")
#     ])
#     rag_chain = qa_prompt | llm
#
#     dataset_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ccas_rag_eval_dataset.json")
#     with open(dataset_path, "r", encoding="utf-8") as f:
#         eval_data = json.load(f)
#
#     print(f"\n🏃 开始全量评测（共 {len(eval_data)} 题）...")
#     samples = []
#     recall_k_hits = 0
#
#     for item in tqdm(eval_data, desc="Running Pipeline"):
#         query = item["user_input"]
#         expected_note_id = str(item["expected_note_id"])
#         user_id = str(item.get("expected_user_id", ""))
#
#         retriever = make_rag_retriever(vectorstore, user_id=user_id if user_id else None)
#         raw_docs = retriever.invoke(query)
#         reranked_docs = rerank_docs(query, raw_docs, top_n=6)
#
#         retrieved_contexts = [d.page_content for d in reranked_docs]
#         retrieved_ids = [str(d.metadata.get("note_id", "")) for d in reranked_docs]
#
#         if expected_note_id in retrieved_ids:
#             recall_k_hits += 1
#
#         context_str = "\n".join(retrieved_contexts)
#         try:
#             actual_response = rag_chain.invoke({"context": context_str, "query": query}).content
#         except Exception as e:
#             actual_response = f"Error: {e}"
#
#         samples.append(
#             SingleTurnSample(
#                 user_input=query,
#                 response=actual_response,
#                 reference=item["reference"],
#                 retrieved_contexts=retrieved_contexts if retrieved_contexts else ["无上下文"]
#             )
#         )
#
#     print("\n" + "=" * 55)
#     print(f"🎯 原生物理命中率 Recall@6: {(recall_k_hits / len(eval_data)) * 100:.2f}%")
#     print("=" * 55)
#
#     print("🧠 正在呼叫裁判模型进行光速评测...")
#     eval_dataset = EvaluationDataset(samples=samples)
#
#     openai_client = OpenAI(
#         api_key=os.getenv("DEEPSEEK_API_KEY"),
#         base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
#     )
#     evaluator_llm = llm_factory(
#         model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
#         client=openai_client
#     )
#
#     if hasattr(evaluator_llm, "client"):
#         evaluator_llm.client.max_tokens = None
#         evaluator_llm.client.max_completion_tokens = 8192
#
#     from ragas.embeddings import LangchainEmbeddingsWrapper
#     evaluator_embeddings = LangchainEmbeddingsWrapper(
#         embeddings=LangchainHFEmbeddings(model_name="BAAI/bge-m3")
#     )
#
#     # 🚀 斩断死循环的关键：AnswerRelevancy 强行设置 strictness = 1
#     answer_rel_metric = AnswerRelevancy(llm=evaluator_llm, embeddings=evaluator_embeddings)
#     if hasattr(answer_rel_metric, 'strictness'):
#         answer_rel_metric.strictness = 1
#
#     # 剔除惹祸的两个指标，换上光速四大金刚
#     eval_metrics = [
#         LLMContextRecall(llm=evaluator_llm),
#         ContextPrecision(llm=evaluator_llm),
#         answer_rel_metric,
#         SemanticSimilarity(embeddings=evaluator_embeddings),
#     ]
#
#     # 恢复正常的超时时间和极速并发
#     run_config = RunConfig(timeout=150, max_workers=6, max_retries=0)
#
#     result = evaluate(
#         dataset=eval_dataset,
#         metrics=eval_metrics,
#         raise_exceptions=False,
#         run_config=run_config
#     )
#
#     print("\n" + "=" * 55)
#     print("📊 RAGAS 光速四强指标最终成绩单：")
#     try:
#         df = result.to_pandas()
#         for col in ['context_recall', 'context_precision', 'answer_relevancy', 'semantic_similarity']:
#             # 兼容输出列名
#             matching_cols = [c for c in df.columns if col.replace('_', '') in c.replace('_', '')]
#             if matching_cols:
#                 print(f" - {col}: {df[matching_cols[0]].mean():.4f}")
#     except Exception:
#         print(result)
#     print("=" * 55)
#
#     output_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ccas_ragas_test_results.csv")
#     try:
#         result.to_pandas().to_csv(output_file, index=False, encoding="utf-8-sig")
#         print(f"\n💾 详细报告已保存至: {output_file}")
#     except Exception as e:
#         print(f"保存 CSV 失败: {e}")
#
# if __name__ == "__main__":
#     main()




"""
eval/test.py - Ragas 0.3.9 四边形战士版 (FactualCorrectness, Faithfulness, Recall, Similarity)
"""

import os
import json
import warnings
from tqdm import tqdm
from dotenv import load_dotenv

# 彻底静默警告
warnings.filterwarnings("ignore", category=DeprecationWarning)
warnings.filterwarnings("ignore", category=UserWarning)

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI
from ragas.dataset_schema import SingleTurnSample, EvaluationDataset
from ragas import evaluate

# 🚀 导入你指定的四大核心指标
from ragas.metrics import (
    LLMContextRecall,
    Faithfulness,
    FactualCorrectness,
    SemanticSimilarity
)

from openai import OpenAI
from ragas.llms import llm_factory
from ragas.run_config import RunConfig

from langchain_huggingface import HuggingFaceEmbeddings as LangchainHFEmbeddings

from app.core.rag import make_rag_retriever, rerank_docs
from app.core.prompts import ROVER_SYSTEM_PROMPT
from app.storage.embeddings import BGEEmbeddings

load_dotenv()

def get_final_chroma_path():
    current_file_path = os.path.abspath(__file__)
    project_root = os.path.dirname(os.path.dirname(current_file_path))
    return os.path.join(project_root, "chroma_db")

def main():
    print("🚀 正在初始化 LabFlow 评测管线 (四边形战士版 - 10样本)...")

    # ── 1. 业务 RAG ──
    llm = ChatOpenAI(
        model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        base_url=os.getenv("DEEPSEEK_BASE_URL"),
        max_tokens=8192,
        temperature=0.1,
    )

    bge_emb = BGEEmbeddings()
    vectorstore = Chroma(
        persist_directory=get_final_chroma_path(),
        embedding_function=bge_emb,
        collection_name=os.getenv("CHROMA_COLLECTION", "black_note_all")
    )

    from langchain_core.prompts import ChatPromptTemplate
    qa_prompt = ChatPromptTemplate.from_messages([
        ("system", ROVER_SYSTEM_PROMPT + """
【相关背景资料】:
{context}

请严格基于上述背景详尽地回答用户的问题。
⚠️ 输出要求：请提取最核心、最有价值的信息，总字数请控制在 120-220 字左右，保持语义连贯完整。不要添加任何 context 中没有的信息。
额外要求：只使用背景资料中明确出现的事实和句子，绝不允许任何推断、总结或润色。"""),
        ("user", "{query}")
    ])
    rag_chain = qa_prompt | llm

    dataset_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ccas_rag_eval_dataset.json")
    with open(dataset_path, "r", encoding="utf-8") as f:
        eval_data = json.load(f)

    print(f"\n🏃 开始全量评测（共 {len(eval_data)} 题）...")
    samples = []
    recall_k_hits = 0

    for item in tqdm(eval_data, desc="Running Pipeline"):
        query = item["user_input"]
        expected_note_id = str(item["expected_note_id"])
        user_id = str(item.get("expected_user_id", ""))

        retriever = make_rag_retriever(vectorstore, user_id=user_id if user_id else None)
        raw_docs = retriever.invoke(query)
        reranked_docs = rerank_docs(query, raw_docs, top_n=6)

        retrieved_contexts = [d.page_content for d in reranked_docs]
        retrieved_ids = [str(d.metadata.get("note_id", "")) for d in reranked_docs]

        if expected_note_id in retrieved_ids:
            recall_k_hits += 1

        context_str = "\n".join(retrieved_contexts)
        try:
            actual_response = rag_chain.invoke({"context": context_str, "query": query}).content
        except Exception as e:
            actual_response = f"Error: {e}"

        samples.append(
            SingleTurnSample(
                user_input=query,
                response=actual_response,
                reference=item["reference"],
                retrieved_contexts=retrieved_contexts if retrieved_contexts else ["无上下文"]
            )
        )

    print("\n" + "=" * 55)
    print(f"🎯 原生物理命中率 Recall@6: {(recall_k_hits / len(eval_data)) * 100:.2f}%")
    print("=" * 55)

    # ── 2. RAGAS 评测 ──
    print("🧠 正在呼叫裁判模型进行四大核心指标评测...")

    eval_dataset = EvaluationDataset(samples=samples)

    openai_client = OpenAI(
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
    )
    evaluator_llm = llm_factory(
        model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
        client=openai_client
    )
    evaluator_llm.max_tokens = 6000          # 裁判专用上限
    evaluator_llm.temperature = 0.0

    if hasattr(evaluator_llm, "client"):
        evaluator_llm.client.max_tokens = None
        evaluator_llm.client.max_completion_tokens = 6000

    # Embeddings 正确包装
    from ragas.embeddings import LangchainEmbeddingsWrapper
    evaluator_embeddings = LangchainEmbeddingsWrapper(
        embeddings=LangchainHFEmbeddings(model_name="BAAI/bge-m3")
    )

    # 忠诚度（严格限制 n=1，防止语句爆炸）
    faithfulness_metric = Faithfulness(llm=evaluator_llm)
    if hasattr(faithfulness_metric, 'n'):
        faithfulness_metric.n = 1

    # 事实正确性（0.3.9 不支持 n 参数）
    factual_correctness_metric = FactualCorrectness(llm=evaluator_llm)

    eval_metrics = [
        LLMContextRecall(llm=evaluator_llm),
        factual_correctness_metric,
        faithfulness_metric,
        SemanticSimilarity(embeddings=evaluator_embeddings),
    ]

    # 10个样本，配置更保守
    run_config = RunConfig(timeout=180, max_workers=4, max_retries=1)

    result = evaluate(
        dataset=eval_dataset,
        metrics=eval_metrics,
        raise_exceptions=False,
        run_config=run_config
    )

    print("\n" + "=" * 55)
    print("📊 RAGAS 四大核心指标最终成绩单：")
    try:
        df = result.to_pandas()
        expected_cols = ['context_recall', 'factual_correctness', 'faithfulness', 'semantic_similarity']
        for col in expected_cols:
            if col in df.columns:
                print(f" - {col}: {df[col].mean():.4f}")
            else:
                matching_cols = [c for c in df.columns if col.replace('_', '') in c.replace('_', '')]
                if matching_cols:
                    print(f" - {matching_cols[0]}: {df[matching_cols[0]].mean():.4f}")
    except Exception:
        print(result)
    print("=" * 55)

    output_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ragas_test_results.csv")
    try:
        result.to_pandas().to_csv(output_file, index=False, encoding="utf-8-sig")
        print(f"\n💾 详细报告已保存至: {output_file}")
    except Exception as e:
        print(f"保存 CSV 失败: {e}")

if __name__ == "__main__":
    main()