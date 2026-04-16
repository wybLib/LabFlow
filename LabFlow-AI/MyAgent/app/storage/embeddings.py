import os
from typing import List

from langchain_core.embeddings import Embeddings
from sentence_transformers import SentenceTransformer

# os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
# os.environ.setdefault("HF_DATASETS_OFFLINE", "1")
# os.environ["HF_HUB_OFFLINE"] = "1"
# os.environ["TRANSFORMERS_OFFLINE"] = "1"

# ==================== 全局单例（只加载一次） ====================
_model = None # 初始化一个全局变量，用来存放模型对象

def _get_model():
    global _model
    # if _model is None:
    #     print("⏳ 首次加载 bge-m3 模型（只需加载一次）...")
    #     _model = SentenceTransformer("BAAI/bge-m3", device="cpu")
    #     print("✅ bge-m3 加载成功")
    # return _model
    if _model is None:
        print("⏳ 正在从本地硬盘加载 bge-m3 模型...")

        # 🚀 1. 动态获取项目的根目录路径
        # 假设当前文件在 app/storage/embeddings.py
        # 向上退三级，回到项目根目录
        base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

        # 🚀 2. 拼接出刚才下载的本地模型的绝对路径  bge-m3 是北京人工智能研究院（BAAI）开源的最强中文检索模型
        local_model_path = os.path.join(base_dir, "local_models", "bge-m3")

        # 增加一个安全检查，如果找不到文件夹，直接报错提示
        if not os.path.exists(local_model_path):
            raise FileNotFoundError(f"❌ 找不到本地模型文件夹！请确认是否已运行下载脚本，且路径存在: {local_model_path}")

        # 🚀 3. 直接传入本地绝对路径，彻底断开网络依赖！
        _model = SentenceTransformer(local_model_path, device="cpu")
        print("✅ 本地 bge-m3 加载成功！")

    return _model

# ==================== 对外使用的 LangChain 接口类 ====================
# 作用：让你的本地模型能伪装成官方接口，直接塞进 Chroma 数据库里用。
class BGEEmbeddings(Embeddings):  # 继承 LangChain 官方接口，确保能被 Chroma 无缝使用
    # 函数作用：把成千上万条“笔记块”批量转成向量
    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        model = _get_model()  # ← 每次都调用同一个模型
        batch_size = 4
        all_embeddings = []
        for i in range(0, len(texts), batch_size):
            batch = texts[i : i + batch_size]
            all_embeddings.extend(
                model.encode(batch, normalize_embeddings=True, show_progress_bar=False).tolist()
            )
        return all_embeddings

    # 把用户当前输入的问题转成向量（用于检索时匹配）
    def embed_query(self, text: str) -> List[float]:
        model = _get_model()  # ← 每次都调用同一个模型
        return model.encode(text, normalize_embeddings=True).tolist()
