# 来源与许可

本仓库是修改后的 OP CarPlay 1349 源码快照，公开整理日期为 2026-10-20。

## 上游

- **xcertplay** — [shilapi/xcertplay](https://github.com/shilapi/xcertplay)，基线提交 [`17c92439413638dfd1d7f91d7e1c2e7358398762`](https://github.com/shilapi/xcertplay/commit/17c92439413638dfd1d7f91d7e1c2e7358398762)，上游版本 1.3.3，GPL-3.0。原版权和代码许可继续保留，OP 修改沿用同一许可。
- **DiPlay** — [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)，参考提交 `6d3b8a8ce927cb4b115b8c3881f17619433e93b1` 的独立仪表显示文档、内容 URL 与命令约定。没有复制比亚迪的车辆显示绑定来冒充 P7 支持。该项目同样使用 GPL-3.0。
- **mhi2_altscreen_carplay** — [harman-f/mhi2_altscreen_carplay](https://github.com/harman-f/mhi2_altscreen_carplay) 是动态视区协议字段的参考，相关功能在本发行构建中默认未启用。
- 上游还致谢 [LIVI](https://github.com/f-io/LIVI) 与 [showcase](https://github.com/amineross/showcase)，这里继续保留。

## 依赖

Shizuku API/Provider 使用 MIT 许可，副本保留在
`common/src/main/assets/licenses/shizuku-MIT.txt`。
Gradle Wrapper 自带 Apache-2.0 头部。其他 Maven/AndroidX/密码学/音频依赖由 Gradle 解析，各自许可不因本项目而改变。

## 图标与品牌

OP 橙色字母图由本项目制作。小鹏图形来源记载在
`asset/xpeng-logo-source.txt`；XPENG、小鹏、Apple、CarPlay 等名称和标志属于各自权利人。
代码开源不代表获得这些第三方商标的转授权，也不代表厂商背书。

## 二次开发声明

本仓库是基于上述固定上游版本修改的 P7 适配快照，修改日期截至 2026-10-20，不是未修改的上游发行版。修改范围包括 P7 窗口/触控、中文分类设置、热点授权、独立仪表输出、原车地图交接及车道条显示；最终行为见 [功能说明](docs/FEATURES.md)。

不公开个人 Git 历史、中间版本、个人认证材料、签名私钥、厂商固件、原厂 APK 或实车个人记录。
