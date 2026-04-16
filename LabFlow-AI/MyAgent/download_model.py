# download_model.py
import os
# 🚀 魔法：设置 Hugging Face 国内加速镜像站！
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
from huggingface_hub import snapshot_download

print("🚀 开始通过国内镜像站下载 BGE-M3 模型...")
print("这可能需要几分钟，请耐心等待（约 2GB 数据）...")

# 将模型下载到项目根目录下的 local_models/bge-m3 文件夹里
local_dir = os.path.join(os.getcwd(), "local_models", "bge-m3")

snapshot_download(
    repo_id="BAAI/bge-m3",
    local_dir=local_dir,
    # 过滤掉不需要的巨大文件（如 PyTorch 以外的模型格式）
    ignore_patterns=["*.onnx", "*.msgpack", "*.h5", "*.safetensors", "coreml/*"]
)

print(f"✅ 下载完成！模型已永久保存在：{local_dir}")