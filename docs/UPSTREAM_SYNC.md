# E01 上游更新记录

本分支以现有 E01 座舱、厂商蓝牙、方向盘按键、仪表媒体和单解码器镜像为基础，逐项回移植上游。
以下记录表示已检查的上游快照和实际移植范围，不表示两个仓库已经完整合并。

- E01 起点：`c8d76e25ec49eb9dd203120f211ef02090a8bd18`。
- 上游：[JingRuirui/DiPlay-Legacy-Android](https://gitee.com/JingRuirui/DiPlay-Legacy-Android)。
- 检查快照：`f40ae15a530820929e800d1fdbb3cf64330f5ae0`，2026-11-23。

## 本批移植

| 上游提交 | 移植内容 | E01 处理方式 |
| --- | --- | --- |
| [`a115c98a`](https://gitee.com/JingRuirui/DiPlay-Legacy-Android/commit/a115c98aadae19a2f1f877a0af3a4972fa06f2a0) | 旧版 Android 解码器跳过强制 8 MB 输入缓冲区 | API 19–22 使用原生端口大小；保留硬解、现有解码队列和仪表镜像。较新系统保留调优，K3 解码器除外。 |
| [`65b88feb`](https://gitee.com/JingRuirui/DiPlay-Legacy-Android/commit/65b88feb2b67b422df04a5ed48fde4e194a6d1c1) | 销毁后的显示窗口不再反复配置解码器 | 移植视频部分；E01 的窗口切换回调在窗口无效时返回失败。蓝牙部分保留本分支的会话代际与收尾逻辑。 |
| [`8b42da3e`](https://gitee.com/JingRuirui/DiPlay-Legacy-Android/commit/8b42da3e5c72acb067e28cd8da891de7436f6d08) | 音频输入阻塞时排出输出；USB 读超时竞态不丢数据 | 复用现有有界输入重试；API 26+ 排空超时请求并保留完成数据。API 19–25 保留现有定时取消与完成数据判断。 |
| [`1a68b4b2`](https://gitee.com/JingRuirui/DiPlay-Legacy-Android/commit/1a68b4b2042e68e48ecc8d154e548968f673ac9f) | 接受短 Opus 包 | 仅移植最小包长度修复，空包仍丢弃。 |
| 上游 CI 的 SDK 安装方式 | 使用 runner 自带 sdkmanager，避免请求已移除的 tools 包 | 保留 E01 的公开源码检查和测试集合；NDK 与本分支声明统一为 `25.2.9519653`。 |

没有导入上游的 AutoChips AC83xx 软件解码优先策略：E01 的 MT6735 不属于该芯片平台。

## Android Auto 与 CarLife

从上游 [`320875d0`](https://gitee.com/JingRuirui/DiPlay-Legacy-Android/commit/320875d00fcbd0631c669daeddd9ed9bb11e85d1) 的 Android 互联模块回移植。入口在 E01 座舱的「设置 → 连接 → 安卓手机投屏」。切换时如有 CarPlay 会话，确认断开后才进入新模式。CarPlay 继续使用 E01 厂商蓝牙和现有仪表链路。

- **Android Auto** 使用上游 AAP 接收器。手机运行 [AARE](https://github.com/prototux/AARE) 时，可以用数据线连接并允许 USB 访问，也可以启用 AARE 的网络接入，在同一 Wi-Fi 下填写手机 IP。车机首次使用时生成并保存自己的接收端证书，Android 5.1 使用构建已包含的 Conscrypt 处理 TLS。
- **百度 CarLife** 使用上游 Wi-Fi 投屏协议。车机和手机加入同一 Wi-Fi 或热点，在手机打开 CarLife+ 并允许投屏。车机会自动查找手机，搜索超时可填写手机 IP。
- 两种模式都可以通过投屏工具条返回 DiPlay。Android Auto 的工具条入口在画面底部，CarLife 在顶部。

上游通用界面、BYD HUD 和独立蓝牙栈仍是另外的适配范围。外置适配器源码继续使用本分支的外部构建输入。

后续继续从这里记录的快照审查差异，不使用 `merge -s ours` 将未移植功能标记为已合并。

## 验证

```sh
python3 scripts/check_public_tree.py
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

新增回归测试检查 API 22 的 MediaFormat、销毁窗口的解码行为、USBMUX 超时与完成/取消竞态。
已有测试继续覆盖 E01 座舱、方向盘和仪表功能。JVM 测试不能证明真实 OMX 驱动或实车投屏已经验证。
Robolectric 4.17 的沙箱从 API 23 起支持；API 22 缓冲区策略通过显式版本参数检查，不声称运行了 Android 5.1 沙箱。

2026-11-23 在 macOS 上运行上述检查：`shared` 625 项（1 项跳过）、`common` 567 项，0 失败；
lint 与调试构建通过。公开源码检查通过。

Android 互联回移植后，`shared` 712 项（1 项跳过）、`common` 567 项，0 失败；lint 与调试构建通过。
在 1920×720 的 Android 5.1 ARM64 模拟器上，从座舱入口按手机 IP 连接模拟手机，检查两种模式的 TCP 握手、H.264 出画、触摸回传和返回座舱。实车 USB 授权、真实手机互通、硬件解码和音频需要实测。

构建仍默认生成不含配件身份、厂商蓝牙载荷和签名的源码测试包，实车包的构建输入见 [BUILD.md](BUILD.md)。
