# Minecraft AI 伴随实体（Fabric 模组）

一个由 LLM 驱动的 Minecraft 伴随实体（NPC 同伴）：会跟随、对话、协助作战与采集，回复同时通过
**GPT-SoVITS** 朗读出来。从原 mineflayer 机器人迁移而来，改用 Fabric 模组形式以绕过
服务器的模组校验。

默认人格为《鸣潮》角色「达妮娅」，可在配置里替换为任意人格（人格、皮肤、语音参考音频均可自定义）。

- **目标版本**：Minecraft `1.21.11` · Fabric Loader `0.19.x` · Fabric API `0.141.4+1.21.11`
- **运行环境**：单人 / 内置服务器（语音在客户端本地播放）
- **状态**：MVP（可编译、可加载）。挖矿 / 收菜 / 战斗 / 回家存箱 / 主动情绪为后续阶段。

## 当前功能（MVP）

- **召唤伴随实体**：`/gf summon` 在身边生成一个人形伴随角色，绑定你为主人。
- **跟随 AI**：跟随主人、闲逛、看向玩家、自动浮水。
- **聊天接 LLM**：在其附近（默认 16 格内）发普通聊天 → 异步调用 LLM → 回复显示在
  聊天框，并触发语音。带 `follow` / `come` / `stop` 工具调用。
- **GPT-SoVITS 语音**：客户端收到台词后 GET 调 TTS 接口播放 WAV，全程异步、失败静默降级。
- **可编辑配置**：首次运行生成 `config/mcgf.json`，可改 LLM/TTS 接口与人格，`/gf reload` 热重载。

> 头顶气泡台词在 1.21.11 改用了新的渲染管线，已推迟到后续阶段；当前台词走聊天框 + 语音，
> 不影响体验。

## 命令

| 命令                        | 作用              |
|---------------------------|-----------------|
| `/gf summon`              | 在身边召唤伴随实体并绑定为主人 |
| `/gf come` / `/gf follow` | 让其过来 / 跟随你      |
| `/gf stop`                | 停在原地            |
| `/gf say <文本>`            | 让其说一句（测试语音/气泡）  |
| `/gf reload`              | 重新加载配置文件        |
| `/gf home`                | 设置家             |

直接在其附近聊天（不带 `/` 或 `!`）即可对话。

## 配置文件 `config/mcgf.json`

首次运行自动生成。关键字段：

- `llm.baseURL` / `llm.apiKey` / `llm.model`：OpenAI 兼容接口（默认指向你的中转）。
  也可用环境变量 `MC_BOT_API_KEY` 或 `OPENAI_API_KEY` 覆盖 key。
- `llm.systemPrompt`：人格设定（`{name}` 会替换为 `persona.displayName`）。
- `tts.url`：GPT-SoVITS 接口，默认 `http://tts.apollodorus.xyz/tts`。
- `tts.refAudioPath` / `tts.promptText` / `tts.promptLang` / `tts.textLang`：参考音频与提示。
- `tts.extraParams`：附加查询参数（如 `{"top_k":"5"}`）。
- `behavior.chatRadius` / `followByDefault` / `moveSpeed` 等。

## 构建

```bash
cd mc-girlfriend
./gradlew build          # 产出 build/libs/mc-girlfriend-<ver>.jar
./gradlew runClient      # 开发环境启动客户端测试
```

把 `build/libs/mc-girlfriend-*.jar`（非 `-sources`）丢进 `.minecraft/mods/`，连同
Fabric Loader + Fabric API 即可。

### 本机构建环境说明（中国大陆网络）

由于 `github.com` 在本机不可达，且 MC 1.21.11 需要 JDK 21，本仓库做了如下适配：

- `gradle-wrapper.properties` 的 `distributionUrl` 指向腾讯云镜像
  （`mirrors.cloud.tencent.com/gradle`）。
- JDK 21 从清华 TUNA 的 Adoptium 镜像下载，解压到 `mc-girlfriend/.jdks/`（已 gitignore）。
- `gradle.properties` 用 `org.gradle.java.home` 让 Gradle 守护进程跑在 JDK 21 上
  （Loom 1.16 要求），并关闭 foojay 自动下载（其源在 github）。

换到能直连 github 的环境时，可移除上述镜像/本地 JDK 设置，恢复标准 toolchain 自动下载。

## 后续阶段（对齐原 mineflayer 能力）

挖矿、收菜、战斗护卫、给物 / 收集任务、回家存箱、主动搭话 / 情绪、记忆压缩、进食生存。
原 JS 实现保留在仓库根目录 `plugins/` 与 `lib/` 作为移植参考。
