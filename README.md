# SpatialPhoto — 非商业科研实验

PICO Spatial SDK 单张照片3D Gaussian Splatting实验。Fullspace拍照，普通窗口查看2D与小角度3D视差，串行后台重建、系统目录导出。默认768模型，目标250000高斯；使用ONNX Runtime CPU推理。

此版本仅供非商业科研实验与学术研究。包含Apple SHARP模型的研究衍生版本，许可、归属和变更见app/src/main/assets/models/LICENSE_MODEL与NOTICE.txt。禁止将此实验包用于许可排除的商业利用或产品开发。并非Apple官方产品或Apple Vision Pro功能移植。

单张图无法补全遮挡区域，侧面/大角度可能缺失，当前版本通过角度限制和有界深度显示供小角度研究。高斯数目与清晰度不等同。重建约一分钟，结果随照片与设备状态变化。照片保存在设备本地，导出由用户选择系统目录授权。

构建：JDK21、Android SDK35/NDK27.2.12479018，./gradlew assembleDebug。model-weights中的分片只为Git传输；Gradle配置阶段校验各分片并还原原始权重，最终SHA256与端侧已验证模型一致，不需要Git LFS或联网下载。

工程不包含开发期设备照片、个人日志、历史交接记录或实验缓存。0.30悬停增强尚待完整佩戴确认，公开展示不得宣称已经完成全部体验验收。
