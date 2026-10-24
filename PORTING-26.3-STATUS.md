# MC 26.3 移植 · 进度状态（2026-10-24 00:00）

> 工作副本：`D:\尝试文件夹\MC MMD\mc263`（从上游 `origin/1.21.5` clone，分支 `port/26.3`）
> **1.20.1 的原始分支完整保留在 `D:\尝试文件夹\MC MMD\handover\handover\work\src\MC-MMD-rust-1.1.0`（未改动）**

---

## 0. 为什么基底换成上游 1.21.5

排查上游时发现：`origin/1.21.5` 的最新提交是 **"适配1.21.5新管线"（92eed8b，2026-07-14）** ——
**上游已经把整个渲染层改成新管线了**（`MmdRenderPipelines` / `MmdGpuDrawDevice` / `MmdRenderRouter`）。
从 1.20.1 移植等于重写 2000 行 GL 代码；从 1.21.5 移植 = 升级 + 适配 26.3，工作量差一个量级。

上游状态：`pushed_at = 2026-07-14`，**没有 26.x 分支**（远端只有 1.20-vr / 1.21.1~1.21.11 / main）。

---

## 1. 已完成 ✅

### 1.1 工具链（已实证）
| 项 | 值 | 状态 |
|---|---|---|
| Minecraft | **26.3** | ✅ |
| Java | **25**（用 Minecraft 自带的 `java-runtime-epsilon`，**不用装新 JDK**） | ✅ |
| Fabric Loom | **1.19.3**（插件 id 必须是 `net.fabricmc.fabric-loom`） | ✅ |
| Gradle | **9.8.2**（loom 1.19.3 要求 ≥ 9.8.0） | ✅ |
| 构建形态 | 纯 Fabric 单模块（去掉 Architectury 的 SRG/transform 链路） | ✅ |

**26.3 关键事实（全部实测）**：
- MC 26.3 的 client jar **未混淆**（`javap` 直接可读类名）；
- **Mojang 不再发布官方映射** → 构建里**必须删掉 `mappings` 那行**；
- Fabric 的 intermediary 对 26.3 是占位符 `0.0.0`；
- `de.oceanlabs.mcp:mcp_config:26.3` **不存在** → Architectury 多模块路线在 26.3 上走不通（纯 Fabric 可以）；
- 渲染 API 整体重写：`RenderType`→`RenderPipelines`、`ShaderInstance`→`ShaderManager`、
  `MultiBufferSource`/`VertexConsumer`→`SubmitNodeCollector`/`StagedVertexBuffer`、
  `LightTexture`→`Lightmap`、`RenderTarget`→`RenderPearl`（`GpuBuffer`/`GpuTexture`/`CommandEncoder`/`RenderPass`）；
- 实体渲染从"立即绘制"改成"提取状态 + 提交"：`render(...)` → `submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)`，
  **不再传 packedLight**（改用 `EntityRenderState.lightCoords`）；
- 大量改名：`ResourceLocation`→**`Identifier`**、`PlayerRenderState`→`AvatarRenderState`、
  `PlayerRenderer`→`AvatarRenderer`、`ItemInHandRenderer`→`FirstPersonHandsAndItemsRenderer`、
  `GuiGraphics`→`GuiGraphicsExtractor`（2D 化，pose 变 `Matrix3x2fStack`）。

### 1.2 机械重命名
`tools/rename263.py` 批量改了 **37 个文件**（22 条 import + 类型名整体替换）。

### 1.3 尸体布娃娃（你的功能）→ Rust 侧已移植且**编译通过**
| 文件 | 改动 |
|---|---|
| `rust_engine/src/ragdoll/{mod.rs,spec.rs}` | 整体从你的 1.20.1 分支搬过来（54KB） |
| `rust_engine/src/lib.rs` | 加 `pub mod ragdoll;` |
| `rust_engine/src/model/runtime.rs` | 加 `ragdoll` / `ragdoll_frozen` 字段 + 初始化 + **7 个方法**（enable/disable/is_active/set_ground_y/apply_world_velocity_delta/set_frozen/debug_info）+ 每帧步进挂接（挂在物理步进之后，且**不受 MMD 物理开关影响**）+ 接管时关 IK |
| `rust_engine/src/skeleton/bone_set.rs` | 加 `ragdoll_bone_indices` 字段 + 3 个方法 + 2 处骨骼传播判定改为 ragdoll-aware |
| `rust_engine/src/physics/bullet_ffi.rs` | 补 `bw_world_refresh_rigid_body` FFI 声明 + `BulletWorld::refresh_rigid_body` 方法（与你的分支逐函数对比，**只差这 2 个**） |
| `rust_engine/src/jni_bridge/runtime_bindings/physics.rs` | 搬入 **202 行**的 7 个 JNI 导出（类名从 `NativeFunc` 改成 `bridge.NativeBindings`） |
| `rust_engine/src/jni_bridge/runtime_bindings.rs` | 补 `JFloatArray` 导入 |

**验证**：`cargo check` → **`Finished dev profile in 55.63s`，0 error**（需带 MSVC 2022 + Windows SDK 10.0.22621.0 的环境变量）。

**⚠️ 还缺**：7 个 JNI 导出对应的 **Java 侧**（`bridge/NativeBindings.java` 加方法 +
`NativeRagdollPort` / `RagdollManager` 等），以及布娃娃渲染要用新管线提交。

### 1.4 构建踩坑备忘
- `build.rs` 读 `deps/bullet3/src/LinearMath` → **bullet3 是 submodule，clone 时不会自动带**，
  要 `git submodule update --init --recursive`（本机已做，commit 对齐 63c4d67）。
- Groovy 里 **`#` 不是注释符**（只有 `//` 和 `/* */`）—— 踩过一次。
- Gradle 的代理参数要用**命令行 `-D`** 传（`GRADLE_OPTS` 只给启动器，不给 daemon）。
- `maven.fabricmc.net` 走代理会超时，**要加进 `nonProxyHosts`**。

---

## 2. 进度：编译错误从 100 → ~77

### 2.1 GPU 渲染层（已完成 ✅）
`MmdGpuDrawDevice` / `MmdRenderPipelines` / `MinecraftTextureDevice` —— 单独 javac 验证 **0 错误**。
最大改动：26.3 **没有逐值 uniform**（`setUniform(String,float)` 整个消失）→ 自建 std140 UBO；
`RenderPipeline.Builder` **不再有默认深度状态**（不写就是完全没有深度测试）；
`ModelViewMat` 变成 `DynamicTransforms` UBO，必须自己绑。

### 2.2 我自己改的（渲染核心 8 个文件）
`EntityMixin` / `FirstPersonCamera` / `LocalPlayerRenderContributor` / `MmdHeldItemRenderer` /
`LegacyEntityMmdRenderer` / `FabricPlayerRendererMixin` / `ItemInHandRendererMixin` / `EntityBaseRenderBridge`

### 2.3 三个子代理并行中
| 代理 | 负责 | 状态 |
|---|---|---|
| A | GPU 渲染层 | ✅ 完成 |
| B | Fabric 平台层（FabricClientRuntimeHooks 等 5 个） | 🔄 进行中 |
| C | GUI 层（20 个文件） | 🔄 进行中（错误列表里 GUI 已清零） |
| D | 相机控制等未认领 8 个文件 | 🔄 进行中 |

---

## 3. 剩下的活（按优先级）

1. **布娃娃的 Java 侧**（你的核心功能）：
   - `bridge/NativeBindings.java` 加 7 个 native 方法声明
   - `NativeRagdollPort` / `RagdollManager` 适配新接口
   - 尸体渲染走新管线提交（`SubmitNodeCollector`）
2. **编译清零** → `:fabric:build` 出 jar
3. **重编 Rust 原生库**（布娃娃改了 Rust，必须重编 dll；`cargo build --release` + 交叉编译 bullet3）
4. **实机验证**：26.3 上尸体是否正常 —— 顺便回答"新架构下那个 bug 会不会消失"

---

## 4. 工具脚本（本机 `D:\尝试文件夹\MC MMD\tools\`）

| 脚本 | 作用 |
|---|---|
| `rename263.py` | 1.21.5→26.3 的 22 条 import + 类型名批量重命名 |
| `apply.ps1` | 按 spec 文件做精确文本替换（保留 CRLF） |
| `port_ragdoll_rust.py` | 布娃娃 Rust 侧移植（从你的分支抽代码块插入，不手抄） |
| `port_ragdoll_rust2.py` | 布娃娃步进挂接 + 骨骼传播 ragdoll-aware |
| `port_ragdoll_jni.py` | 7 个 JNI 导出搬到新 bridge 结构 |
| `build-common.ps1` | 纯 javac 编 common（~40 秒，比 Gradle 快） |
