# op13t-sensors

OPPO 一加 13T（op13t）传感器全量面板。单 Activity + WebView + `@JavascriptInterface` 桥，
原生侧只负责传感器枚举与批量监听，展示层全部在内嵌 HTML 里完成。

- 列出设备**全部传感器**（`getSensorList(TYPE_ALL)`，一个不漏）
- 每个传感器显示静态规格 + **实时参数**（~5Hz 节流批量推送）
- 触发型传感器（显著运动/步检测等 one-shot）如实标注，触发后自动重挂等待下次
- 无网络权限；心率/计步类监听需运行时授权（申请逻辑内置）
- aarch64 主机（DGX Spark）手动工具链构建：javac → d8 → aapt2(box64) → apksigner

## 构建

```bash
# 依赖: JDK 21, Android SDK (build-tools 34.0.0 + platforms;android-34), box64
bash apk/build.sh
# 产物: op13t-sensors-v<git tag>.apk（仓库根目录）
```

版本号与 versionCode 由 git tag 派生（`major*10000 + minor*100 + patch`），
与 tookies / asi-z 项目同一套约定。

## 迭代计划

| 版本 | 内容 |
|------|------|
| v0.1.0 | 全量列表 + 实时值（初版，用于评估哪些传感器值得集成） |
| 后续   | 按真机反馈筛选/精简，成熟后作为"传感器"模块并入 tookies |

与 android-sys-info → asi-z → tookies 系统信息模块同一条流水线。
