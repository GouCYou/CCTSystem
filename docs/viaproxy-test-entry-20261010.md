# ViaProxy 旧版本测试入口（2026-10-10）

正式 Velocity 保持 modern 转发，现有入口不变。测试路径：1.8.9 客户端 → ViaProxy（25568）→ 发送 1.13 协议到现有 Velocity（127.0.0.1:25566）→ 原有登录、大厅及玩法服。

选择固定目标 1.13 是因为该协议已满足 modern 握手条件，且不要求新版客户端的玩家公钥；当前 Velocity Via 系列继续将其转换到各子服版本。部署的是 ViaProxy 官方 3.4.14 发布版。

## MCSManager 启动设置

新建一个普通进程实例，名称可用 ViaProxy，工作目录 `/www/cctstudio2/ViaProxy`。启动命令：

```text
/usr/lib/jvm/java-25-openjdk-amd64/bin/java -Xms256M -Xmx1G -Duser.timezone=Asia/Shanghai -Djava.awt.headless=true -DskipUpdateCheck=true -jar ViaProxy-3.4.14.jar config viaproxy.yml
```

停止命令：`stop`。无需启动脚本。启动由用户执行。

启用导航兼容修复需要用户重启 Velocity；子服无需因此重启。插件 JAR 已替换，版本仍为 1.0.0。低于1.21不会调用 setServerLinks；低于1.21.6继续使用 `/navigation`、`/nav` 的聊天菜单。原生 ESC 对话框不能显示在1.8.9客户端。

## 连接地址

- 同一局域网：`192.168.1.6:25568`
- 有 IPv6 网络的公网客户端：`6.cctstudio.cn:25568`
- 当前没有为此测试端口增加公网 IPv4 FRP 转发；不能假定正式公网域名加该端口即可访问。

## 配置

- bind-address `[::]:25568`：Linux IPv6双栈监听，同时接受局域网IPv4。
- target-address `127.0.0.1:25566`，target-version `1.13`。
- backend-haproxy true：传递原始来源IP到已经启用HAProxy协议的Velocity。
- frontend-haproxy false：测试入口由Minecraft客户端直接连接。
- proxy-online-mode false、auth-method NONE：保留现有AuthMe登录路径，不引入共享正版账号。
- wildcard-domain-handling NONE：客户端不能通过握手指定其他代理目标。
- rewrite-handshake-packet false：保留玩家连接域名。
- ignore-protocol-translation-errors false：测试阶段保留转换失败，避免吞掉证据。

## 验证边界

导航插件构建及16项测试通过，其中新增测试覆盖1.8、1.13、1.20.5不发送服务器链接以及1.21、1.21.11继续发送链接。部署前比较JAR，唯一变动的类为 CCTNavigation.class。

官方 ViaProxy 3.4.14 已通过本地隔离协议测试：模拟1.8客户端（协议47）到模拟1.13服务端（协议393），验证玩家名、HAProxy v2来源IP，以及服务端响应反向转换。该测试未连接正式服务器。

部署文件SHA-256核对一致，文件所有者为www：

- ViaProxy-3.4.14.jar：`2894bbfb2f4342f8fde887af6c689313efd5b0e46a8006775eea0e21e2948ab5`
- CCTNavigation-1.0.0.jar：`3838a73a77e948a79b5011c161b1074c490554c4c846f1457b4150f8fc443383`

导航原JAR备份：`/www/cctstudio2/backup/viaproxy-navigation-20261010-153207/CCTNavigation-1.0.0.jar`。

未启动、重启或重载服务器实例。完整1.8.9登录、聊天、UUID、IP、皮肤、切服及Duels真实对局需要用户启动后验证，不能以静态配置和构建检查代替实测。

参考：[ViaProxy 官方发布](https://github.com/ViaVersion/ViaProxy/releases/tag/v3.4.14)、[官方配置源码](https://github.com/ViaVersion/ViaProxy/blob/main/src/main/java/net/raphimc/viaproxy/protocoltranslator/viaproxy/ViaProxyConfig.java)。
