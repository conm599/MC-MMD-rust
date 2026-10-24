# MC 26.3 移植 · 最终状态（2026-10-24）

> 工作副本：`D:\尝试文件夹\MC MMD\mc263`（从上游 `origin/1.21.5` clone，分支 `port/26.3`）
> **上游 1.20.1 的原始分支完整保留在 `D:\尝试文件夹\MC MMD\handover\handover\work\src\MC-MMD-rust-1.1.0`（未改动）**

---

## 0. 为什么基底是上游 1.21.5 而不是 1.20.1

上游 `origin/1.21.5` 的最新提交是 **"适配1.21.5新管线"（92eed8b，2026-07-14）**——
上游已经把整个渲染层改成新管线（`MmdRenderPipelines` / `MmdGpuDrawDevice` / `MmdRenderRouter`）。
从 1.20.1 移植等于重写 2000 行 GL 代码；从 1.21.5 移植 = 升级 + 适配 26.3，工作量差一个量级。

本分支 `port/26.3` **直接派生自 92eed8b**，所以上游 PR 的 diff 天然是 1.21.5 → 26.3。

---

## 1. 工具链（已实证）

| 项 | 值 | 状态 |
|---|---|---|
| Minecraft | **26.3** | ✅ |
| Java | **25**（用 Minecraft 自带的 `java-runtime-epsilon`，**不用装新 JDK**） | ✅ |
| Fabric Loom | **1.19.3**（插件 id 必须是 `net.fabricmc.fabric-loom`） | ✅ |
| Gradle | **9.8.2**（loom 1.19.3 要求 ≥ 9.8.0） | ✅ |
| 构建形态 | 纯 Fabric **单模块**（去掉 Architectury 的 SRG/transform 链路） | ✅ |
| NeoForge | `net.neoforged.moddev` 2.0.148 + `neoforge 26.3.0.97` | 见 §4 |

### 26.3 关键事实（全部实测）

- MC 26.3 的 client jar **未混淆**（`javap` 直接可读类名）；
- **Mojang 不再发布官方映射** → 构建里**必须删掉 `mappings` 那行**；
- Fabric 的 intermediary 对 26.3 是占位符 `0.0.0`；
- `de.oceanlabs.mcp:mcp_config:26.3` **不存在** → Architectury 多模块路线在 26.3 上走不通；
- 渲染 API 整体重写：`RenderType`→`RenderPipelines`、`ShaderInstance`→`ShaderManager`、
  `MultiBufferSource`/`VertexConsumer`→`SubmitNodeCollector`/`StagedVertexBuffer`、
  `LightTexture`→`Lightmap`、`RenderTarget`→`RenderPearl`（`GpuBuffer`/`GpuTexture`/`CommandEncoder`/`RenderPass`）；
- 实体渲染从"立即绘制"改成"提取状态 + 提交"：`render(...)` → `submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)`，
  **不再传 packedLight**（改用 `EntityRenderState.lightCoords`）；
- GUI 改成**抽取式**：`render(...)` → `extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)`，
  背景单独走 `extractBackground(...)`；`GuiGraphicsExtractor.centeredText` 的 **x 是水平中心**；
- 大量改名：`ResourceLocation`→**`Identifier`**、`PlayerRenderState`→`AvatarRenderState`、
  `PlayerRenderer`→`AvatarRenderer`、`ItemInHandRenderer`→`FirstPersonHandsAndItemsRenderer`；
- 鼠标输入：GLFW 的 `onPress(long,int,int,int)` → 26.3 的 `onButton(long,MouseButtonInfo,int)`，
  **按键编号 GLFW(0=左 1=右 2=中) → SDL(1=左 2=中 3=右)**，`InputConstants.MOUSE_BUTTON_LEFT == 1`；
- 相机：`Camera.setup(...)` 已删除 → 26.3 是 `Camera.update(DeltaTracker)` + `alignWithEntity(float)`；
- 26.3 走 **Vulkan**（无 GL 上下文）→ 初始化路径里调 `GL11.*` 会让 LWJGL **不可捕获地 abort 整个 JVM**；
- 深度是 **reversed-Z**；
- GUI 坐标换算：游戏的 `MouseHandler.getScaledXPos(window)` = `原始像素 × getGuiScaledWidth() ÷ getScreenWidth()`；
  `Gui` 渲染屏幕时传的 mouseX/mouseY 用的也是它。

### 构建踩坑备忘

- `build.rs` 读 `deps/bullet3/src/LinearMath` → **bullet3 是 submodule，clone 时不会自动带**，
  要 `git submodule update --init --recursive`；
- Groovy 里 **`#` 不是注释符**（只有 `//` 和 `/* */`）；
- Gradle 的代理参数要用**命令行 `-D`** 传（`GRADLE_OPTS` 只给启动器，不给 daemon）；
- `maven.fabricmc.net` 走代理会超时，**要加进 `nonProxyHosts`**；
- 代理对**大 POST / 长连接**不稳 → 推 GitHub 要分批（`tools/push_batch.ps1`，每批 15 提交）；
- **换 jar 必须在游戏停止时做**（运行中替换会导致 `ZipFile invalid LOC header` 崩溃）；
- 源码统一 **CRLF + UTF-8 无 BOM**（`tools/normalize_crlf.py`）；
- `.ps1` 必须**纯 ASCII**（PS 5.1 按 GBK 读，中文注释会导致解析失败）。

---

## 2. 已完成并**实机验证** ✅

| 功能 | 验证方式 | 证据 |
|---|---|---|
| 模型渲染（PMX/PMD + 贴图 + 动画） | 26.3 实例实机 | 纳西妲模型（29511 顶点 / 23 材质）正常显示 |
| GPU 蒙皮 + CPU 回退 | 实机 + 性能 HUD | `gpu_skinning` 可切换 |
| 死亡布娃娃（物理尸体 + 到期冻结） | 实机 + 日志 | `[Ragdoll] 尸体登记 / 初始化 / 物理保持到期点，尸体冻结: id=1 (60 s)` |
| 死亡屏劫持（透明屏 + 右下角两按钮） | 实机截图 | 按钮渲染在右下角，悬停高亮正常 |
| 「复活」按钮 | 实机点击 | 点后死亡屏关闭、玩家满血复活 |
| 「自由观战」按钮 | 实机点击 + 鼠标移动 + 滚轮 | 相机接管，环视改变视角、滚轮拉远 |
| 第一人称头部裁剪 | 实机截图 | 三角形级掩码 `headMask(active=true, vertices=10072/29511)`，头部不再挡镜头 |
| 模型分发（服务器转发） | 专用服务端 + 客户端 + 第二客户端 | 房主 → 玩家清单推送成功 |
| 模型分发（OSS 直链） | 模拟 OSS（MD5 当 ETag） | 客户端直取 12 个文件，服务端**零块转发** |
| 布娃娃设置页 | 实机 | 9 个条目（该页在 26.3 移植中整页漏掉，已补回） |
| 死亡物理保持时长滑条 | 编译 + 配置对齐 | 上限 300 → **3600**，与 `ConfigData` 的 `[0,3600]` 对齐（默认仍 60） |

### 已修复的根因（都有实机证据，不靠猜）

1. **性能调试 HUD 秒崩**：26.3 走 Vulkan 无 GL 上下文，`OpenGlMemoryProbe` 调 `GL11.glGetString`
   → LWJGL 不可捕获地 abort JVM。改为 `backendName()` + `GL.getCapabilities()` 两道纯 Java 判定，HUD 再包 try/catch。
2. **第一人称头部隐藏不生效**（三层根因）：
   ① 掩码绕过 `replace_material_visibility()` → `material_revision` 不自增 → draw command 缓存不刷新；
   ② 眼睛骨骼按名取到头顶控制骨骼 `両目`(y=15.39) 而非 `左目/右目`(y=12.08)；
   ③ 材质 `肌2` 同含身体+头（54 顶点高于眼位），材质级救不了 → 改**三角形级裁剪**
   （任意顶点被掩码则整块丢弃，避免尖刺）。
3. **死亡屏按钮点不动**：自己手写的 GUI 坐标换算**乘除颠倒**
   （写成 `原始像素 × Screen.width ÷ getGuiScaledWidth()`，正确是 `原始像素 × getGuiScaledWidth() ÷ getScreenWidth()`）。
   在 854×480 / GUI scale=2 上 `Screen.width` 恰好等于 `getGuiScaledWidth()`（都是 427），
   公式退化成"直接返回原始像素坐标"，正好是正确值的**两倍** → 点击永远落在矩形之外。
   修法：直接 shadow 游戏的 `MouseHandler.getScaledXPos/getScaledYPos`，判定坐标与渲染坐标从此同源。
4. **轮盘顶部相邻分区标签压字**：CJK advance 9px，顶部两锚点间距 36.34px 而「模组设置」宽 36px → 净间隙 0.34px。
   改为同行业务标签强制留 8px 间隙、优先等比缩小。
5. **不分发界面勾选框/文字对比度不足**：勾选框用 `0x24000000`（14% 透明）等于隐形 + 文字被 HUD 透出冲淡
   → 改深底亮描边 + 垫深色底。
6. **Stage「缺 19 个文件」**：路径 diff 假阳性，Stage 早已完整移植；真缺口只有相机 Roll + `StageAnim` 误报。

---

## 3. 已完成但**无法实机验证**（受环境限制）

| 项 | 原因 | 证据强度 |
|---|---|---|
| **NeoForge 端 26.3 的运行时行为** | 本机 `versions/` 只有 26.2/26.3 的 **Fabric** 实例，跑不了 NeoForge | 编译期全绿 + ASM 注入点逐条核对（见 §4） |
| 材质控制 / 表情选择 / 场景放置 / 生物替换界面 | 合成输入限制（无法稳定触发这些界面路径） | 代码路径已核对 |
| 死亡观战的鼠标环视与滚轮缩放（**已修复并验证**） | — | 见 §2 |
| `spec.rs` 的 1.7m 体型标定在模型放大后是否跟随缩放 | **待用户拍板**（行为未定义） | — |

---

## 4. NeoForge 端状态 —— **已移植** ✅（编译期）

`neoforge/` 目录的 1.21.5 形态源码已按 26.3 重写：**27 个文件重写 + 9 个新建（共 36 个）**，
对齐 fabric 端的 32 个。`:neoforge:build` exit 0。

### 4.1 为什么必须换掉 Architectury

`dev.architectury:architectury-neoforge` **没有 26.x 版本**（最高 21.0.5，对应 MC 1.21.x），
且 Architectury 的 SRG/transform 链路需要 `de.oceanlabs.mcp:mcp_config:26.3`（Mojang 侧不存在）。
所以改用官方 `net.neoforged.moddev` **2.0.148** + `neoforge` **26.3.0.97**。

```groovy
// 根 build.gradle：Loom 只给 :fabric，ModDevGradle 只给 :neoforge
id 'net.fabricmc.fabric-loom' version '1.19.3' apply false
id 'net.neoforged.moddev'  version '2.0.148' apply false

// neoforge/build.gradle
neoForge { enable { version = "26.3.0.97"; disableRecompilation = true } }
```

`common/` 不再是独立 Gradle 模块，源码/资源目录直接挂到两个平台的 `sourceSets.main`。

### 4.2 26.3 API 迁移要点（NeoForge 侧）

| 领域 | 1.21.5 | 26.3 |
|---|---|---|
| 实体渲染 | `render(...)` / `MultiBufferSource` | `submit(...)` / `SubmitNodeCollector` |
| 相机 | `Camera.setup(...)` | `Camera.update(DeltaTracker)` + `alignWithEntity(float)` |
| GUI | `GuiGraphics` | `GuiGraphicsExtractor`（`centeredText` 的 x 是**水平中心**） |
| 鼠标 | `onPress(long,int,int,int)`（GLFW 0=左 1=右 2=中） | `onButton(long, MouseButtonInfo, int)`（SDL 1=左 2=中 3=右） |
| 渲染状态扩展 | `RegisterRenderStateModifiersEvent` + `ContextKey` | **该事件在 26.3 已删除** → 直接给 `LivingEntityRenderState` 挂扩展 |
| 按键分类 | `KeyMapping.Category`（字符串） | `KeyMapping.Category`（对象）+ `RegisterKeyMappingsEvent.registerCategory` |
| 实体附件 | `serialize(Codec)` | `serialize` 只收 `IAttachmentSerializer` 或 `MapCodec` |
| 服务端对象 | `ServerPlayer.getServer()` | `player.level().getServer()` |
| 事件总线 | `@EventBusSubscriber(bus = MOD/GAME)` | 26.3 **没有 `bus` 属性**了，事件自带 `IModBusEvent` 即走 mod 总线 |

### 4.3 产物

`neoforge/build/libs/mmdskin-neoforge-1.0.5-26.3.jar`：
`META-INF/neoforge.mods.toml`（`loaderVersion = "[12,)"`，对应 FML 12.0.12）、
`META-INF/jarjar/`（内嵌 cloth-config-neoforge-26.3.159 + jlayer-1.0.1）、
`natives/windows-x64/mmd_engine.dll`、`assets/mmdskin/lang/*.json`。

### 4.4 验证强度与未验证项

- ✅ `:neoforge:build` exit 0（compileJava + processResources + jarJar + jar 全绿）
- ✅ `:fabric:build` 仍 exit 0
- ✅ 用 ASM 写的注入点校验器把每个 `@Inject` / `@At` / `@Shadow` 按「名字 + 描述符」
  在 `minecraft-patched-26.3.0.97.jar` 里逐条核对，全部 OK
  （NeoForge 26.3 的 mixin fork 不带 mixin 注解处理器，javac 无法校验注入点）
- ❌ **没有实机运行过**（本机没有 MC 26.3 的 NeoForge 实例）。运行时行为
  （渲染顺序、事件触发时机、YSM 兼容反射签名）仍需实机确认
- ⚠️ `disableRecompilation = true` 跳过 NeoForm 反编译/重编译，产物是打过
  NeoForge 二进制补丁的等价 jar，只是没附源码


---

## 5. 剩余可做的事

1. **房主 → 玩家（局域网发现）分发的最后一步**：清单推送已验证，"点下载"这一步因房主进程被误杀没跑完。
2. **`spec.rs` 1.7m 体型标定**是否跟随模型缩放 —— 待用户拍板。
3. **上游 PR**：GitHub 要求跨仓库 PR 的 head 必须是 base 的 fork。
   本仓库 `conm599/MC-MMD-rust-26.3` 是独立仓库（`isFork=false`），compare API 返回 404、
   建 PR API 返回 `422 head invalid`。用户已决定**暂不开上游 PR**，先把本仓库做完整。

---

## 6. 工具脚本（本机 `D:\尝试文件夹\MC MMD\tools\`）

| 脚本 | 作用 |
|---|---|
| `rename263.py` | 1.21.5→26.3 的 22 条 import + 类型名批量重命名 |
| `normalize_crlf.py` | 统一 CRLF + UTF-8 无 BOM |
| `launch-mc.ps1` / `restart-mc.ps1` | 部署 jar 并启动 26.3 实例 |
| `mcinput.py` | Win32 截图 / 键盘 / 鼠标（`postkey` `posttext` `postclick` `postmove` `scroll`） |
| `push_batch.ps1` | 分批推送 GitHub（每批 15 提交，规避代理 408） |
| `oss_server.py` / `check_oss.py` | 模拟 OSS + 校验直链分发 |
| `setup_dist_server.py` / `setup_joiner.py` | 搭分发专用服务端与第二客户端 |
| `scan_secrets.py` | 公开仓库推送前扫敏感信息 |
| `pmx_inspect.py` / `diff_firstperson.py` | PMX 结构解析 / 第一人称差异比对 |
| `javap` 辅助 | `javap -p -c -cp 'S:\client-26.3.jar' <类>` 是判断 API 是否存在的唯一可靠依据 |
