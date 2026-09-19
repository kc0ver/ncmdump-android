# 应用没有反射 / 序列化需求，R8 规则保持精简。
# 原生可执行文件 libncmdump.so 通过 jniLibs 打包，不参与混淆。

-dontwarn org.jetbrains.annotations.**
