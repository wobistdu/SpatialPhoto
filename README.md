# 浮光 · 空间照片

PICO 头显上的单图空间重建研究应用。在 Fullspace 取景拍照，在普通窗口查看原图或小角度移动头部观察空间照片。后台逐张重建，可继续拍摄、浏览及导出原图与 PLY。

## 致谢与许可

本项目使用 Apple 研究项目 [SHARP](https://github.com/apple-aiml-research/ml-sharp) 的模型及权重，感谢 SHARP 作者的研究工作。相关论文为 [Sharp Monocular View Synthesis in Less Than a Second](https://arxiv.org/abs/2512.10685)。

随附模型是基于 SHARP 的实验性衍生版本，经过 768 输入适配、ONNX 导出和 W8 权重量化。上游来源、权重及修改记录见 [模型来源说明](app/src/main/assets/models/NOTICE.txt) 和 [W8 变更记录](app/src/main/assets/models/W8_CHANGES.txt)。

Apple Machine Learning Research Model is licensed under the Apple Machine Learning Research Model License Agreement. 完整条款见 [Apple 模型许可](app/src/main/assets/models/LICENSE_MODEL)。模型及其衍生版本仅供许可规定的非商业科学研究和学术活动，许可排除商业利用和产品开发。Apple 的 [SHARP 代码许可](https://github.com/apple-aiml-research/ml-sharp/blob/main/LICENSE) 和 [第三方致谢](https://github.com/apple-aiml-research/ml-sharp/blob/main/ACKNOWLEDGEMENTS) 可在上游查看。

本应用使用 [ONNX Runtime](https://github.com/microsoft/onnxruntime)、PICO Spatial SDK 和 AndroidX 等依赖，各组件适用各自的许可和分发条款。应用自身代码目前未声明独立开源许可，公开访问本仓库不构成对该代码的额外授权。

## 构建

需要 JDK 17 或以上、Android SDK 35、NDK 27.2.12479018、CMake 3.22.1。配置 ANDROID_HOME 或在 local.properties 中指定 sdk.dir。从工程根运行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

默认构建 VibeLab 应用 com.example.spatialphoto，版本 0.34-w8（34）。同一套源码可用 -PlocalW8=true 构建 com.example.spatialphoto.w8，对应已验收的本地相册。两个包使用独立的私有存储。

## 模型与资产

768 像素输入，最多 250,000 个高斯。CPU 运行 ONNX Runtime。model-weights 下按序存放 W8 权重分片，Gradle 在生成 APK 前还原并验证模型图与完整权重的 SHA-256。图、权重许可及变更说明在 app/src/main/assets/models。商店封面、详情图和图标在 release-assets。
