# HIFI 参数实测结论（2026-11-17，PD1610 + LOS16 + 3.18.71 预编译内核）

> 本文记录 APP 所依赖的 HAL 参数**实机验证结果**，纠正此前"按 strings 推断"的错误。

## 一、核心结论：真正生效的是 `enable_hifi`，不是 `hifi-playback`

### 1.1 参数有效性对照

| 参数写法 | `adev_set_parameters` 收到 | `hifi state change` 日志 | `HiFi Mode Switch` 变化 | 结论 |
|---|---|---|---|---|
| `hifi-playback=on` | ✅ | ❌ 无 | ❌ | **无效** |
| `hifi-playback=1` | ✅ | ❌ 无 | ❌ | **无效** |
| `hifi-playback=true` | ✅ | ❌ 无 | ❌ | **无效** |
| `hifi-playback=HiFi` | ✅ | ❌ 无 | ❌ | **无效** |
| **`enable_hifi=1`** | ✅ | ✅ **`hifi state change from 0 to 1`** | ✅ HiFi | **有效** |
| `enable_hifi=0` | ✅ | ✅ `from 1 to 0` | ✅ Off | **有效** |
| `DSDEnable=1` | ✅ | ❌ | ❌ | **未验证出有效写法** |

`readback` 也印证：`getParameters("enable_hifi")` 返回 `enable_hifi=true` / `enable_hifi=false`。

### 1.2 为什么 `hifi-playback` 不起作用

HAL 只把它当普通 key 收下（`hash_map_utils: key: 'hifi-playback' value: ''` —— value 解析为空），
没有进入 HIFI 状态机。真正的开关变量名是 `enable_hifi`。

## 二、改参数后必须重建输出流

实测顺序对比：

```
场景 A：enable_hifi=0 → 播放
   Apply path: headphones                      ← 普通耳机路由
   HiFi Mode Switch = Off

场景 B：enable_hifi=1 → 播放
   Apply path: headphone-hifi                  ← HIFI 路由！
   HiFi Mode Switch = HiFi
```

**原因**：HAL 在 `start_output_stream` 时才根据 hifi 状态选择 mixer path。
已经建立的输出流不会因参数改变而重新路由。

**因此 APP 的交互必须提示**：切换 HIFI 后要重新开始播放（换播放器/重新点播放）。
APP 里已写："切换后需重新开始播放才生效（HAL 在开流时才应用 headphone-hifi 路由）"。

## 三、HIFI 通路已具备的底层条件

| 条件 | 状态 |
|---|---|
| `mixer_paths_tasha.xml` 含 `headphone-hifi` / `headphone-hifi-DSD` | ✅（232 条 headphone 相关 path） |
| `audio_platform_info.xml` 含 `SND_DEVICE_OUT_HEADPHONE_HIFI` → backend `headphone-hifi`，interface `TERT_MI2S_RX-and-SLIMBUS_6_RX` | ✅ |
| HIFI 电路供电 `hifi_3v3` / `hifi_4v8` | ✅（模块 service.sh 开机自动开启） |
| 耳机检测（HIFI 只对耳机生效） | ✅（jackd 桥接） |

`headphone-hifi` path 的 5 个控件（最小可复现集）：

```xml
<path name="headphone-hifi">
    <ctl name="TERT_MI2S Bit Format" value="S24_LE" />
    <ctl name="TERT_MI2S_RX Channels" value="Two" />
    <ctl name="HiFi Mode Switch" value="HiFi" />
    <ctl name="Headphone Rx Mixer I2S_RX" value="1" />
    <ctl name="TERT_MI2S mode" value="Slave" />
</path>
```

注意 `TERT_MI2S mode=Slave`（HIFI 时 SoC 作从机，由外部 DAC 主控），
与普通耳机/外放的 `Master` 不同。

## 四、APP 编译方法（本机环境，无标准 Android SDK）

本机没有独立 Android SDK，用 AOSP 树里的工具拼出编译环境：

```bash
# 假 SDK 目录
SDK=/home/builder/fakesdk
BT=$SDK/build-tools/35.0.0
AOSP=/home/builder/los16

ln -sf $AOSP/out/host/linux-x86/bin/aapt      $BT/aapt
ln -sf $AOSP/out/host/linux-x86/bin/aapt2     $BT/aapt2
ln -sf $AOSP/out/soong/host/linux-x86/bin/d8  $BT/d8
ln -sf $AOSP/prebuilts/sdk/tools/linux/bin/zipalign  $BT/zipalign
cp    $AOSP/prebuilts/sdk/tools/linux/bin/apksigner  $BT/apksigner   # 必须是真实文件
ln -sf $AOSP/prebuilts/sdk/tools/linux/lib/apksigner.jar $BT/apksigner.jar
ln -sf $AOSP/prebuilts/sdk/28/android.jar     $SDK/platforms/android-28/android.jar
```

**踩过的四个坑**：

1. **javac 必须用 `-classpath`**，不能用 `-bootclasspath`
   → 否则报 "Unable to find package java.lang"
2. **apksigner 脚本按自身路径找 jar**
   → 脚本必须是真实文件（不能软链），jar 要放在**与脚本同目录**（`$BT/apksigner.jar`）
3. **`aapt package` 不能带结尾的 `.`**
   → 老版 aapt 会失败；而且 aapt 不会打包 classes.dex
   → 正确流程：aapt 生成 base.apk → `zip -j` 追加 classes.dex → zipalign → apksigner
4. **`signapk.jar` 在本机跑不了**（`UnsatisfiedLinkError: NativeCrypto`）
   → 用 apksigner 而非 signapk

完整脚本见 `rebuild_apk.sh`。

## 五、APP 已实现的功能

| 功能 | 实现方式 | 状态 |
|---|---|---|
| 读 DT 硬件参数（DAC 型号/参数表/增益/阻抗阈值） | `DtReader` 直接读 `/sys/firmware/devicetree/base/` | ✅ 实测可读 |
| HIFI 模式切换 Off/HiFi/DSD | `enable_hifi=0/1` + `DSDEnable=1` | ✅ HIFI 档已验证；DSD 档待验 |
| DSD 音量 0..31 | `dsdvol_index=<n>` | ⚠️ 调用成功但未验证实际效果 |
| I2S 时钟档位 | `hifi_i2s_clock_rate=<hz>` | ⚠️ 调用成功但 readback 空，未验证 |
| 显示当前 HIFI 状态 | `getParameters("enable_hifi")` | ✅ 显示 `enable_hifi = true` |
| 备份/恢复 mixer | root + 文件复制 | ✅（需 Magisk 授权） |

## 六、待验证 / 已知限制

1. **DSD 档**：`DSDEnable=1` 未观测到 `HiFi Mode Switch=DSD`，需要继续找正确的 key 或组合
2. **`dsdvol_index` / `hifi_i2s_clock_rate`**：调用返回成功但 readback 为空，
   实际是否影响音质需用 `logcat` + 听感验证
3. **HIFI 音质差异**：技术上通路已切换到 `headphone-hifi`（S24_LE + I2S Slave），
   听感差异取决于音源和耳机，需实机听测
4. **APP 需要 Magisk 授权**（root），首次启动会弹窗；DT 读取和 mixer 备份需要它，
   纯 HIFI 切换不需要（`MODIFY_AUDIO_SETTINGS` 普通权限即可）

---

# 补充：DSD 与音质的进一步实测（2026-11-17 11:20）

## 七、DSD 档为什么触发不了（三层限制）

### 7.1 `DSDEnable` 参数无效

| 操作 | `HiFi Mode Switch` | path |
|---|---|---|
| `enable_hifi=1;DSDEnable=1` | HiFi | `headphone-hifi` |
| `enable_hifi=1;DSDEnable=0` | HiFi | `headphone-hifi` |
| `tinymix 2121 DSD`（手动置控件） | DSD | 仍是 `headphone-hifi` |

**结论**：`DSDEnable` 不影响 path 选择，手动改控件 HAL 也不认。

### 7.2 HAL 的真实逻辑

HAL 里的字符串说明了一切：

```
always select HiFi DSD when its thread is writing data
AUDIO_OUTPUT_FLAG_HW_DSD, mIsDSD:%d
standby here since dsd mode change
```

**DSD 路径由"输出流本身是 DSD 格式"决定**，不是参数开关。

### 7.3 Java 层根本不让你输出 DSD

`AUDIO_FORMAT_DSD = 0x1A000000`（`system/media/audio/include/system/audio-base.h:99`），
但实测 `AudioTrack` 直接拒绝：

```
getMinBufferSize = -2                                    → 不支持
Invalid encoding 436207616 (0x1A000000)                  → AudioFormat$Builder.setEncoding 抛异常
```

**所以**：

| 方案 | 可行性 |
|---|---|
| 普通 APP 用 AudioTrack 输出 DSD | ❌ Java 层白名单拒绝 |
| 手动改 mixer 控件 | ❌ HAL 不认 |
| `DSDEnable` 参数 | ❌ 无效 |
| 支持原生 DSD 直出的播放器（如 Poweramp 的 `output.athd`） | ⚠️ 理论可行，需在播放器内开启 Hi-Res/DSD 输出并播真实 DSD 音源 |

测试文件已生成：`/sdcard/Music/dsd_test.dsf`（DSD64 / 立体声 / 1-bit / 2,822,400 Hz / 5 秒 / 3.4 MB，
结构已校验：magic `DSD `、fmt chunk 52、block size 4096、数据 0x69/0x96 交替静音）。

用 Poweramp 打开时报 `Pipeline2: handleOnTrackStarted no pending track`，未进入播放。

## 八、HIFI 音质的客观差异（控件级证据）

同一播放内容，仅切换 `enable_hifi`，key 控件对比：

| 控件 | HIFI OFF | HIFI ON | 说明 |
|---|---|---|---|
| `HiFi Mode Switch` | Off | **HiFi** | 模式 |
| `TERT_MI2S Bit Format` | **S16_LE** | **S24_LE** | 位深 16 → 24 |
| `TERT_MI2S mode` | **Master** | **Slave** | SoC 从 I2S 主机变从机（时钟由外部主控，降抖动） |
| `Headphone Rx Mixer I2S_RX` | **Off** | **On** | I2S 接收通路打开 |
| `HPHL Volume` | **16** | **20** | 左耳音量 +4 |
| `HPHR Volume` | **16** | **20** | 右耳音量 +4 |
| `RX HPH Mode` | CLS_H_LP | CLS_H_LP | 无变化 |
| `DSD SampleRate Switch` | Off | Off | 无变化 |
| `HiFi DSD Volume Switch` | 19 | 19 | 无变化 |

**对应 path**：

```
OFF: Apply path: headphones              / low-latency-playback headphones
ON : Apply path: headphone-hifi          / low-latency-playback headphone-hifi
```

**所以 HIFI 模式在技术上确实生效**，改变的是：位深、I2S 主从、通路开关、耳机音量。

**注意（听感陷阱）**：HIFI ON 时耳机音量自动从 16 升到 20（+4 档），
单纯 A/B 对比很容易把"更响"误判成"音质更好"。**公平对比需要先把两边响度对齐**。

**位深 24bit 的实际收益**：取决于音源。若音源本身是 16bit/44.1k，24bit 通路的收益主要来自
数字音量/混音的余量，听感提升有限；若播放 24bit 高解析音源，收益才明显。
