# 取消订阅比加入组织还难
Minecraft Java 1.21.1 / NeoForge / Java 21

按 **O** 打开会员中心。一键开通后，需要经过十道挽留关卡才能取消。状态只在当前游戏进程中保存，重启会重置。

## 编译
使用 Java 21 和 Gradle 8.10+，在项目根目录运行 `gradle build`。
产物在 `build/libs/cancel-subscription-1.0.0.jar`。
将 JAR 放入 Minecraft 1.21.1 NeoForge 客户端的 `mods` 目录。

**注意**：这是源码项目，尚未在本环境实际执行 Gradle 编译和游戏内测试；如遇映射或依赖版本问题，需要根据安装的 NeoForge 21.1.x 调整。仅客户端模组，无真实支付功能。

## GitHub 网页编译
1. 新建公开或私有空仓库，不勾选初始化 README。
2. 在仓库主页选择 Add file → Upload files，上传本 ZIP **解压后的全部文件和目录**（包括 `.github/workflows/build.yml`）。
3. 打开 Actions → Build NeoForge Mod → Run workflow。
4. 成功后在运行页面的 Artifacts 下载 JAR。
注意：此项目未在当前环境完成依赖下载与实际编译，云端可能报 API/Gradle 兼容错误。
