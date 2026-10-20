# 1349 最终功能说明

面向老小鹏 P7 E28 / Android 9，公开版本 v0.7，构建号 1349。

## 主屏 CarPlay

无线连接 iPhone，提供主屏画面、触控、音频、麦克风及媒体控制。复用原车热点，在设置中填写实际热点信息和认证方式。

支持三种显示方式：原车分屏、带原车系统栏的全屏、完全全屏。完全全屏隐藏原车状态栏与 Dock；带栏模式按可用区域避让。画面上的小图标切换模式，设置可选择默认状态，初始为完全全屏。

窗口尺寸变化可能触发重协商/重连，未承诺无缝切换。有线 CarPlay 相关上游代码保留，但老 P7 有线连接未完成实车验收。

主要代码：`CarPlayHostActivity`、`P7WindowCompat`、`P7ContentInsets`、`P7MeasuredViewport`、`FullscreenModeState`、`DisplayResizeState`。

## 设置与操作

中文设置采用左侧分类、右侧内容。支持五指收拢或三指下滑呼出设置；退出入口关闭 OP 并释放仪表投放。

设备名称、默认全屏模式、认证方式、音视频等参数可配置。应用采用橙色 OP 图标。

主要代码：`SettingsPanelView`、`FiveFingerPinchGesture`、`AirPlayPersistence`、`AppRunState`。

## 原车热点

可选自动开启原车热点，默认关闭。使用原车既有热点配置，不更改名称、密码或频段；原车配置为 5 GHz 时复用该配置。功能是否适用取决于固件与实际权限。

应用内保留本机 ADB 与 Shizuku 两种授权入口：

- 本机 ADB 已开启，并允许 OP 的连接；
- Shizuku 已运行，并允许 OP。

热点授权仅设置本应用 `WRITE_SETTINGS` AppOp；不能开启关闭中的 ADB。用户退出 OP 后暂停自动恢复，等待下一次打开或适用的启动事件。

主要代码：`NativeHotspotApi`、`NativeHotspotService`、`HotspotRecovery`、`HotspotGrantCommand`、`LocalAdbHotspotAuthorization`、`ShizukuHotspotAuthorization`。

## 原车仪表地图

**此功能仅测试了高德地图，其他地图应用尚未验证。**

请求 iPhone 独立的 type 111 地图流，与中控主画面分开。使用 P7 的居中取景配置，通过已授权 shell 辅助进程提交到原车导航卡片区域，不显示中控右上角预览。

输入画布为1328×664；真实输出以原车实时请求为准，实测660×660、5fps。输入尺寸不代表仪表可用区域尺寸。高德第二路的3D、车道级、范围和缩放由地图应用决定，不能保证与主屏完全一致。

投放时只暂停固定的原车小地图生产服务，退出时通过原车 RESET 路径重建。它不是整仪表替换，也不是 CarPlay Ultra。

主要代码：`ClusterLabDisplay`、`ClusterMapRenderer`、`P7InstrumentFraming`、`InstrumentFrameServer`、`P7InstrumentWorker`、`P7ImagePolicy`、`P7NativeMapGuard`。

## 原车蓝色车道条

可选“OP 投放时隐藏原车车道提示”，默认开启。

- 实际开始投放后立即清除，不等待原车新回调。
- 投放期间定期维持，收到新车道信息时更新恢复缓存。
- 断开、退出或关闭选项后停止清除；有缓存则尝试恢复，没有缓存则等待原车后续事件。
- 不能保证没有缓存时旧蓝条立即恢复。原车小地图画面恢复与蓝条恢复是两条独立流程。

只发送 lane 显示字段，不修改全局导航模式、转向、定位或车辆控制。

主要代码：`P7LaneLease`、`P7NativeLaneBridge`、`P7InstrumentController`。

## 认证与公开发行

保留本地文件、硬件及远程认证配置。公开 APK 不内置个人证书或私钥，需要用户自己提供有权使用的认证方式；不会自动为用户开通远程服务。

公开源码包含可选的外部认证资源构建入口，但本次公开发行不使用它。源码和发行资产不包含离线安装包、安装 command、私有签名材料或原厂固件。
