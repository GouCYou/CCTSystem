# CCTSystem

CCTStudio 群组服统一核心。相同的 JAR 可安装在 Velocity 与 Paper，运行时根据平台、`server-id`、角色、依赖和配置加载功能。

## 构建

需要 Java 21：

```bash
./gradlew clean test shadowJar
```

产物位于：

```text
cctsystem-distribution/build/libs/CCTSystem-0.1.0-SNAPSHOT.jar
```

部署时两个平台的数据目录大小写不同：Paper 使用 `plugins/CCTSystem/config.yml`，Velocity 使用插件 ID 对应的 `plugins/cctsystem/config.yml`。两端仍安装同一个 `CCTSystem.jar`。

架构、模块、数据模型与部署顺序见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。生产密钥和数据库密码必须在部署阶段注入，不得提交到仓库。
