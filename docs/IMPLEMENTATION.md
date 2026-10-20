# 实现与边界

## 主屏

`CarPlayHostActivity`、`P7WindowCompat`、`P7MeasuredViewport`、`P7ContentInsets`、`P7WindowPreferences` 处理 P7 窗口与设置。完全全屏、原车系统栏及分屏的有效区域分别测量，视频协商尺寸和触控映射跟随可用区域。

保留 `DisplayResizeState`、`DisplayReconnectState` 等重连处理。默认构建不启用动态 view-area；不能从相关代码存在推断无缝切换已经可用。

## 第二路仪表

```text
iPhone 地图应用
  → 独立 CarPlay type 111 视频
  → OP 独立解码 / ClusterMapRenderer
  → 居中取景 + 实时请求尺寸的 JPEG
  → 本机受限 socket
  → 已授权 shell P7InstrumentWorker
  → 原车导航图像接口
  → P7 仪表原生导航卡片
```

主屏 type 110 与仪表 type 111 分开。P7 当前配置向 iPhone 请求 1328×664 画布，按 `P7InstrumentFraming` 为高德偏右的锚点取景；仪表输出由原车实时请求控制，本次实车为 660×660、5 fps。不是直接把主屏截成正方形。

`P7ImagePolicy` 检查请求、尺寸、帧率与 JPEG 边界。`InstrumentFrameServer` 和 shell worker 验证 socket 对端身份；worker 通过车型标识及 API28 检查，仅在匹配的老款 P7、既有 shell 身份和可用原车接口条件下运行。它不是通用远程命令服务。

`P7NativeMapGuard` / `P7MiniMapLease` 仅管理固定的
`com.xiaopeng.montecarlo.service.minimap.MiniMapService`。
取得通道前确认原生产者停止，交回时通过
`com.xiaopeng.montecarlo.minimap.RESET` 重建绘图表面。工作进程 EOF、超时与退出触发释放。没有停止整个原车导航包。

## 蓝色车道条隐藏

车道条与地图图片不是一个图层。`P7NativeLaneBridge` 通过既有原车接口仅发送：

```json
{"lane":{"isLaneShow":false,"laneType":0,"frontLane":[],"backLane":[],"tollGateInfo":[]}}
```

不附带全局导航模式、定位、转向、车速或车辆控制字段。写入直接到 lane 显示接口，避免自己的清除再次成为 XUI 订阅事件。

`P7LaneLease` 负责：

1. OP 开始投放时立即取得车道条显示的临时控制，不要求先收到快照。
2. 保存可用的原车快照；未知则保存 `await_oem` 标记。
3. 定期清除，合并新事件，避免定期清除每次都写恢复文件。
4. 停止投放时交回；已知快照尝试写回，未知则等待原车后续事件。
5. 异常恢复只允许同一开机、足够新鲜的快照，避免跨路口回放陈旧箭头。

监听并不保证能读到已经显示的旧蓝条，因此“没有缓存时不能立即复原”是明确的行为边界。调用返回计数只代表接口返回。

## 原生热点

`NativeHotspotApi` 封装原车 Android 9 tethering 路径；`HotspotRecovery`、`NativeHotspotService`、`BootReceiver` 管理触发与退出暂停。

`HotspotGrantCommand` 是固定的本包 `WRITE_SETTINGS` AppOp 设置及回读。`LocalAdbHotspotAuthorization` 与 `ShizukuHotspotAuthorization` 提供两种已有权限入口。未新增常驻 root 或关闭中的 ADB 启动方式。

## 公开源码范围

编译不需要拷贝原车系统 APK、仪表固件或反编译代码；厂商接口在车辆运行时解析，缺失或权限拒绝时应报不可用。本仓库没有提供厂商固件，也没有刷写功能。

私有认证和签名材料不是源码依赖。`OP_MFI_ASSETS_DIR` 是个人构建的可选输入；公开发行构建必须不设置该变量。认证 Provider 的选择逻辑保留，便于自定义构建。
