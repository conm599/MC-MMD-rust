# NeoForge 平台模块 —— 26.3 **未移植**（当前不参与构建）

## 现状

- `settings.gradle` 里**只有 `include 'fabric'`**，本模块不参与任何 Gradle 构建，
  产物里也不会出现它的 class。
- 本目录的源码仍是 **MC 1.21.5 时代**的形态，直接拿来用会立刻失败：

| 文件 | 问题 |
|---|---|
| `mixin/neoforge/MouseHandlerMixin.java` | `@Inject(method = "onPress", ...)` + `int button`（旧 GLFW 签名）。26.3 已改名为 `onButton(long, MouseButtonInfo, int)`，且鼠标键编号从 GLFW（0=左 1=右 2=中）变成 SDL（1=左 2=中 3=右） |
| `mixin/neoforge/CameraMixin.java` | `@Inject(method = "setup", ...)`。26.3 的 `Camera.setup` 已删除，拆成 `update(DeltaTracker)` + `alignWithEntity(float)` |
| `mixin/neoforge/*` 其余 | 普遍是 1.21.5 的 `render(...)` / `MultiBufferSource` 入口，26.3 已改成 `submit(...)` / `SubmitNodeCollector` |
| `config/ModConfigScreen.java` | cloth-config 的 `AbstractConfigListEntry.render(...)` 已改名 `extractRenderState(...)` |
| `build.gradle` | 依赖 `net.neoforged:neoforge` + `net.neoforged:moddevgradle`，版本指向 1.21.x；且它引用的 `:common` 模块在 26.3 移植里已被并入 `:fabric` |

## 为什么没做

MC 26.3 上 **Architectury 多模块路线走不通**：它需要 `de.oceanlabs.mcp:mcp_config:26.3`，
而这个版本在 Mojang 侧**不存在**。所以 26.3 移植被压成了**纯 Fabric 单模块**
（`common` 的源码/资源目录直接挂到 `:fabric` 的 sourceSet 上）。

## 要恢复 NeoForge 支持需要做什么

1. 放弃 Architectury，改为 **NeoForge 的官方 Gradle 插件（`net.neoforged.moddev`）** 单模块，
   或者自己把 `common` 重新拆成独立 Gradle 模块（但不能用 Architectury 的 SRG/transform 链路）。
2. 确认 26.3 对应的 NeoForge 版本（`26.3-66.1.0` 一类）以及它的 Gradle 插件坐标。
3. 按 `fabric/` 的做法重写本目录：mixin 目标全部对齐 26.3、渲染入口改 `submit(...)`、
   GUI 改 `extractRenderState`、鼠标键编号改 SDL。
4. `common` 拆出去后，`fabric` 和 `neoforge` 两个平台的 mixin 会有一份重复代码
   ——建议抽成 `common` 下的平台无关 mixin 基类。

## 参考

`fabric/` 目录里是**已经适配好 26.3** 的等价实现，可以直接对照着搬：
`CameraMixin` / `MouseHandlerMixin` / `MinecraftMixin` / `LivingEntityRenderStateMixin` 等。
