"""
文本清理与 chunk 预处理模块（独立维护）
负责：Markdown 结构化分块 + 针对性清理 + 短块合并
"""

"""
面试/简历考点解析：
为什么不直接用 RecursiveCharacterTextSplitter？

答：单纯按字符数切会破坏笔记的逻辑。先用 MarkdownHeaderTextSplitter 是为了尊重原作者的写作结构，保留标题层级信息。

chunk_size 为什么设置 700？

答：这是一个平衡点。太小了（如 100）语义碎片化；太大了（如 2000）检索精度下降且浪费 Token。700 字大约对应 BGE-M3 模型最佳的语义承载范围。

为什么在分块前后都做了图片链接的清理？

答：前置清理是为了不干扰标题结构的识别；后置清理是为了防止切分过程中产生的残余碎片污染最终的 Chunk。
"""

import re
from typing import List

from langchain_core.documents import Document
from langchain_text_splitters import MarkdownHeaderTextSplitter, RecursiveCharacterTextSplitter


def clean_text(text: str) -> str:
    """基础 Markdown 友好清理（Step 2 前置）"""
    if not text:
        return ""

    #清洗一：字符编码清洗  AI 模型如果遇到大量不可见字符，计算出的向量值会产生剧烈偏移，导致检索失效。
    text = "".join(c for c in text if c.isprintable()) #isprintable() 过滤掉不可见的特殊字符（如控制字符、乱码），防止这些噪音干扰 Embedding 向量模型的编码
    # 只清理多余空行，保留单个换行（Markdown 结构依赖换行）
    #利用正则表达式去除对检索无意义的内容
    #清洗二：正则表达式 (RegEx)。数据清洗中的原子级工具，用于精准模式匹配
    text = re.sub(r" +", " ", text.strip())   # 只压缩空格，不动换行
    text = re.sub(r"\n{3,}", "\n\n", text)    # 3个以上换行压成2个 保留 Markdown 的段落感（\n\n），但删掉无意义的连续空白。

    # 删除常见垃圾 图片链接（![alt](url)）在纯文本向量化时毫无意义，反而占用 Token。
    text = re.sub(r"^\s*[-*_]{3,}\s*$", "", text, flags=re.MULTILINE)  # 分隔线
    text = re.sub(r"!\[.*?\]\(.*?\)", "", text)                        # 图片链接

    if not re.search(r'[\w\u4e00-\u9fff]', text):
        return ""

    return text.strip()


def preprocess_and_chunk(raw_text: str, metadata: dict) -> List[Document]:
    """
    【Step 2: Split】完整预处理 + 分块入口（核心函数）

    流程：
    1. clean_text（基础清理）
    2. MarkdownHeaderTextSplitter（按标题结构切）
    3. RecursiveCharacterTextSplitter（长度控制）
    4. 二次过滤 + 短块合并
    """
    if not raw_text:
        return []

    # 1. 基础清理
    cleaned = clean_text(raw_text)
    if not cleaned:
        return []

    # 2. Stage 1：结构化分块（保留标题层级）  按照 Markdown 的标题层级（H1-H4）进行初步切割
    #预处理一：结构感知分割。相比暴力切割，这种方式能确保同一标题下的内容被优先聚在一起。strip_headers=False 保证标题本身不被删掉，因为标题里通常含有极高的语义权重。
    header_splitter = MarkdownHeaderTextSplitter(
        headers_to_split_on=[
            ("#", "Header 1"), ("##", "Header 2"),
            ("###", "Header 3"), ("####", "Header 4"),
        ],
        strip_headers=False
    )

    # 3. Stage 2：长度控制分块  针对 Stage 1 切出来的文档，如果某个章节太长（超过 700 字），再进行二次切分
    #预处理二：滑动窗口 (Sliding Window)。重叠区（Overlap）是为了防止语义被拦腰切断。比如第 1 块提到了“周杰伦”，第 2 块开头有重叠，AI 就能知道上下文。
    recursive_splitter = RecursiveCharacterTextSplitter(
        chunk_size=700,
        chunk_overlap=150, #相邻两个块之间有 150 字的重叠
        length_function=len,
        separators=["\n\n", "\n", ". ", "！", "。", " ", ""], #优先级递减的切分符。先尝试按段落切，不行再按句子，最后按空格
    )

    docs = header_splitter.split_text(cleaned)
    docs = recursive_splitter.split_documents(docs)

    # 4. 二次过滤 + 清理
    final_chunks = []
    for doc in docs:  #对切好的每一块进行终审
        content = doc.page_content.strip()

        # 删除图片链接和纯分隔线   ...再次清理图片和分隔线...
        content = re.sub(r'!\[.*?\]\(.*?\)', '', content)
        content = re.sub(r'^\s*[-*_]{3,}\s*$', '', content, flags=re.MULTILINE)

        # 过滤无效 chunk  少于 50 字的块（可能只是个标题或废话）直接扔掉，因为太短的内容没有足够的特征，会造成检索干扰。
        if len(content) >= 50 and re.search(r'[\w\u4e00-\u9fff]', content): #[\w\u4e00-\u9fff] 确保块里必须含有文字（中英文字符），过滤掉纯符号块。
            chunk_metadata = metadata.copy() #元数据继承 每个 Chunk 都必须携带原始笔记的 note_id、user_id 等信息，这样 AI 查到这一块时，才知道它属于哪篇笔记。

            final_chunks.append(Document(
                page_content=content,       # 清洗版 → 用于 embedding 检索
                metadata=chunk_metadata,
            ))

    print(f"[Cleaner] 本次笔记处理完成 → 生成 {len(final_chunks)} 个有效 chunk")
    return final_chunks