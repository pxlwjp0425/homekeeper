# 机顶盒会"杀后台"吗？—— 实测报告（2026-10-08）

> 现象：用爱奇艺看电视剧 → 按 HOME 回桌面 → 再从桌面进爱奇艺 → **回到首页，播放页没了**。
> 实测环境：某国产机顶盒，Android 11 / API 30，4GB RAM。

## 结论（TL;DR）

> ⚠️ **2026-10-08 深夜重大订正**：第一版把账算在「首页 `HomeActivity` 是 singleTask」上，**是错的**。
> 真机复现证明：**真实播放页在按下 HOME 的 1 秒内就被爱奇艺自己销毁了**，与 launchMode 无关。
> 详见 **第八章**。下面第二、六、七章保留原文以记录排查过程（已标注订正）。

1. **不是被系统杀。** 进程 PID 全程不变、内存充裕（`MemAvailable` 1.7G/4G），LMK 没动手。
2. **是爱奇艺自己把播放页销毁了** —— 且**在离开 App 的那一刻（按 HOME 后 1 秒内）就销毁**，
   不是等你重新点图标才清。真实播放页是**插件 Activity（`com.gala.video.plugin.epg` 里的
   `PlayerActivity`）由宿主代理 `InstrActivityProxy0` 承载**。
3. **改不了**：宿主清单（launchMode）无关；插件 APK 有 MD5 校验不能改；没有「后台播放」开关；
   盒子不支持画中画。**看剧中途回桌面再回来接着看，这台盒子上做不到。**
4. 盒子上**唯一真会"清后台"**的东西是系统自带「最近任务」界面底部的**「一键优化」**按钮。
5. 实际替代：回桌面后再进爱奇艺，首页第一行就有「**你正在追 / 继续观看**」，一键回到该剧。

---

## 一、证明"不是被杀"

实验：启动爱奇艺 → 用二级页把栈撑成 2 层（模拟"播放页压在首页之上"）→ 按 HOME → 从桌面重进。

| 步骤 | 进程 PID | 进程已运行 | 爱奇艺任务栈 |
|---|---|---|---|
| 启动后 | 9616 | 00:11 | `sz=1` |
| 打开二级页 | 9616 | — | `sz=2` |
| **按 HOME 回桌面** | 9616 | 03:59 | **`sz=2`（完好保留）** |
| **从桌面重进** | 9616 | 04:08 | **`sz=1`（二级页被销毁）** |

- **PID 从头到尾都是 9616** → 进程一次都没有重启过。
- `oom adj: cur=200`（远不是待杀边缘），`lastPss=293MB`。
- 内存 `MemAvailable = 1.7GB / 4GB` —— **内存一点都不紧张**，LMK 没有理由动手。
- 按 HOME 之后任务栈 `sz=2` 完整保留，说明"后台"是好好的；
  真正发生变化的时刻是**重新点开图标的那一下**。

## 二、~~真凶：爱奇艺把首页声明成了 singleTask~~（⚠️ 本章结论已作废，见第八章）

把 APK 里的 AndroidManifest 解出来（`aapt2 dump xmltree`）统计：

```
activity 总数 = 72
launchMode 分布: standard 52 / singleTask 20
clearTaskOnLaunch=true 的: 无
alwaysRetainTaskState=true 的: 无
```

入口和播放页都在 singleTask 名单里：

```
singleTask  com.gala.video.app.epg.HomeActivity        ← 桌面图标指向的就是它
singleTask  com.gala.video.app.player.PlayerActivity   ← 播放页
```

Android 对 `singleTask` 的定义（官方文档原文）：

> If an instance of the activity already exists, the system routes the intent to the existing
> instance through a call to its `onNewIntent()` method... **Meanwhile all of the other activities
> on top of it are destroyed.**

所以看剧时的栈 `[HomeActivity, PlayerActivity]`，一旦从桌面重新启动 `HomeActivity`：

```
[HomeActivity, PlayerActivity]  --点桌面图标-->  PlayerActivity 被销毁  -->  [HomeActivity]
      ↑ 正在看剧                                                              ↑ 回到首页
```

系统自己的日志把这句话写得很直白，每次重进都会打印：

```
Warning: Activity not started, its current task has been brought to the front
```

### 与桌面 App 无关（三种启动方式对照）

| 启动 flag | 含义 | 结果 |
|---|---|---|
| `0x10200000` | NEW_TASK + RESET_TASK_IF_NEEDED（**本桌面当前用法**，AOSP 原生桌面也是这个） | `sz=2 → sz=1` |
| `0x10000000` | 只有 NEW_TASK | `sz=2 → sz=1` |
| `0x0` | 不带 flag | `sz=2 → sz=1` |

**三种结果完全一致** → 不是桌面代码的问题，是 singleTask 的必然行为。

## 三、换别的视频 App 也一样

| App | 入口 Activity | 启动模式 |
|---|---|---|
| 银河奇异果（爱奇艺） | `com.gala.video.app.epg.HomeActivity` | **singleTask** |
| CIBN酷喵（优酷） | `com.youku.tv.home.activity.HomeActivity` | **singleTask** |
| CIBN酷喵（备用入口） | `com.yunos.tv.yingshi.home.HomeActivity` | **singleTask** |

## 四、唯一真会"清后台"的东西：「一键优化」

系统自带「最近任务」界面 = `com.skyworthdigital.recentsmanager/.RecentTaskActivity`
（响应 action `com.skyworthdigital.recentsmanager.RECENT_TASK`）。

界面顶部写着「**按【OK】键打开应用，按【上键】关闭**」，底部有个「**一键优化**」按钮。

实测点一下「一键优化」：

- 右上角任务数从「**共4个应用**」变成「**共1个应用**」
- 屏幕提示「**恭喜您清理了 248M 内存**」
- 爱奇艺的任务被移除（`task = 空`；进程 PID 没立刻死，但**任务被清掉了**，
  再点图标就是全新启动）

**所以：如果按过这个按钮，那才是真的"后台全没了"。**

## 五、顺带发现：这台盒子的"最近任务"也救不了播放页

从「最近任务」列表里选中爱奇艺按 OK → 同样 `sz=2 → sz=1`（前台回到 `HomeActivity`）。
说明厂商这个实现是"**重新启动应用**"，而不是 Android 原生的 `moveTaskToFront`（恢复任务），
所以它也绕不开 singleTask 的销毁行为。

（参照：AOSP 原生 Recents 用 `ActivityManager.moveTaskToFront(taskId)` 恢复任务，是可以保留栈的。）

---

## 给使用的建议

| 想做的事 | 能不能做到 |
|---|---|
| 看剧中途按 HOME 回桌面，之后**继续看** | ❌ 做不到 —— 播放页在按 HOME 那 1 秒内就被爱奇艺自己销毁了（订正见第八章）|
| 用别的视频 App 规避 | ❌ 没用，酷喵等同样是 singleTask |
| 用「最近任务」恢复 | ❌ 这台盒子的实现是重新启动 |
| 从爱奇艺的「播放记录 / 继续观看」续播 | ✅ 可行，回到首页后一键续播 |

**另外请尽量不要按「一键优化」** —— 它会把所有后台任务清空，包括你正在用的其它 App。

## 复现实验的脚本片段

```bash
ADB=/d/Android/Sdk/platform-tools/adb.exe; S=192.168.0.9:5555
PKG=com.gitvvideo.skyworthopen
HACT=$PKG/com.gala.video.app.epg.HomeActivity
SUB=$PKG/com.gala.video.app.setting.SettingActivity      # 任意 standard 的二级页

sz(){ $ADB -s $S shell 'dumpsys activity activities' \
      | grep -oE "Task\{[^}]*$PKG[^}]*sz=[0-9]+" | grep -oE 'sz=[0-9]+' | head -1; }

$ADB -s $S shell "am start -n $SUB -f 0x10000000"; sleep 3   # 撑成 2 层
$ADB -s $S shell "input keyevent 3";               sleep 3   # 按 HOME
#   -> 栈仍为 sz=2（没被杀）
$ADB -s $S shell "am start -a android.intent.action.MAIN \
       -c android.intent.category.LAUNCHER -n $HACT -f 0x10200000"; sleep 4
#   -> 栈变 sz=1（播放页/二级页被销毁）
```

截图证据：`dist/iqx-3-recents.png`（最近任务界面 + 一键优化）、`dist/iqx-5-optimize-focus.png`、
`dist/iqx-6-after-optimize.png`（清理后 4→1）。

---

# 六、能不能绕过去？（2026-10-08 追加实测）

> ⚠️ **本章结论已被第八章推翻**：这里的「改 launchMode 有效」是在**实验室仿真的第二层**上得出的，
> 真实播放页根本不是那种组件。保留原文以记录排查过程。

**桌面侧绕不过去；改爱奇艺自己可以 —— 但要付代价。**

## 6.1 桌面侧三条路，全部堵死

| 思路 | 为什么不行 | 证据 |
|---|---|---|
| 点图标时先查栈顶、再启动栈顶页面 | 桌面 App 没权限读别的 App 的任务栈 | 需 root |
| 让桌面借盒子自带 root 后门提权 | 后门只给 shell 组用 | 见下 |
| 直接拉起播放页 `PlayerActivity` 来恢复 | 播放页不接受空 intent，会立刻自退 | 见下 |

```
$ ls -l /system/xbin/usrchan
-rwsr-x--- 1 root shell 8524 /system/xbin/usrchan      ← 其它用户连 x 权限都没有

$ run-as com.xiaopaitech.wallpaperlauncher sh -c "echo <口令> | /system/xbin/usrchan root id"
sh: /system/xbin/usrchan: can't execute: Permission denied
```

> 把后门权限改成 `o+x` 就能让桌面用上，但那等于**给盒子上每个 App 都开了 root**，不建议。

外部单独拉起播放页：

```
$ am start -n com.gitvvideo.skyworthopen/com.gala.video.app.player.PlayerActivity -f 0x10000000
Starting: Intent { flg=0x10000000 cmp=.../PlayerActivity }
→ 7 秒后前台已回到桌面，爱奇艺 task 消失（自己退出了）
```

## 6.2 改爱奇艺自己：~~**实测有效**~~（仅在实验室仿真下成立，对真实播放页无效）

同一个 APK，造了两个只差 `launchMode` 的启动器入口，其余条件完全相同：

| 组 | 入口 launchMode | 点图标重进后的栈 | 播放页 |
|---|---|---|---|
| G1 | **standard** | `[Entry, Second]`，`sz=2` 原样保留 | ✅ **保住** |
| G2 | singleTask（爱奇艺现状） | `[Entry]`，`sz=1` | ❌ 被销毁 |

（第二层用 singleTask 页面模拟播放页；重进用的是真实 Launcher intent，
即 `MAIN + LAUNCHER + NEW_TASK + RESET_TASK_IF_NEEDED` = `0x10200000`。）

**改法**：把 `HomeActivity` 的 `launchMode="singleTask"` 改成 `standard`；
更外科手术的做法是保留 HomeActivity 不动，另加一个 standard 的 `<activity-alias>` 作为图标入口。

条件也具备：

- 工具链 `/d/Tools/apktool/apktool.jar`（3.0.3）+ build-tools 35.0.0 的 `zipalign`/`apksigner`（海马爸比补丁同款流程）
- APK **没有加固**：6 个明文 `classes*.dex`，无 `libjiagu`/`libDexHelper`
- 爱奇艺是**纯用户安装**（`/data/app/...`），卸载不影响系统

## 6.3 代价

| 代价 | 说明 |
|---|---|
| **必须卸载重装** | 重打包换了签名，无法覆盖安装 → **登录态与观看记录全丢**，要重新登录 |
| **VIP / DRM 有风险** | TV 版对播放链路有客户端校验，重签后小概率被服务端拒绝 |
| **官方升级即失效** | 每次爱奇艺更新都会覆盖补丁，需重打 |
| **可能有副作用** | 首页由 singleTask 变 standard 后，**爱奇艺内部**再打开首页会新建实例，返回键可能要多按几下 |

---

# 七、动手改包（2026-10-08 深夜）——⚠️ 补丁是惰性的，没解决问题

> **订正**：这一章记的改动确实装上去了、签名校验也都通过，但**对「重进回首页」一点作用没有**。
> 原因见第八章：播放页在按 HOME 时就已经没了，压根轮不到启动模式登场。
> 这段保留下来，是因为补丁脚本本身（`iqpatch/`）在将来仍可复用。

按第六章的结论真改了，装机验证通过。

## 改了哪 4 个字节

`AndroidManifest.xml` 里 HomeActivity 的 `android:launchMode`：`2`(singleTask) → `0`(standard)。

```
[元素] <activity android:name="com.gala.video.app.epg.HomeActivity">
   theme              type=REF      data=2131558564
   name               type=STRING   data=126     -> "com.gala.video.app.epg.HomeActivity"
   launchMode         type=INT_DEC  data=2  →  0    ← 只改这里，偏移 34412 (0x866C)
   screenOrientation  type=INT_DEC  data=0
   configChanges      type=INT_HEX  data=1073758207
```

**没走 apktool 重建**：这个包有 6 个 dex（48MB）+ 一堆 so，重建会重排压缩方式，
还要伺候 `doNotCompress` 列表（jpg/png/*.so 必须保持不压缩），风险大。
而 launchMode 是定长 `INT_DEC`，**原地改 4 字节既不改变长度、也不移动任何偏移**，
APK 里另外 1459 个条目字节级原样保留。

## 产物与回滚

| 文件 | 说明 |
|---|---|
| `dist/iqiyi-16.9.0.221243-standard.apk` | 已签名（v1+v2+v3 均 verified），装机用的就是它 |
| `D:/tmp/iqwork/iq-original.apk` | **原包备份**，SHA256 `83ab299f…e292364`，出问题装回它即可 |
| `D:/tmp/iqwork/iq.keystore` | 签名密钥（alias `iq`，口令 `android`） |
| `iqpatch/iqpatch.py` | 一键补丁脚本（自动定位 + 改字节 + 剔旧签名） |
| `iqpatch/axml_scan.py` | 只读扫描器，用于复核"到底改成了什么" |

## 装机实测结果

| 步骤 | 改前 | 改后 |
|---|---|---|
| 撑两层 → 按 HOME → 点图标重进 | `sz=2 → 1`，播放页被销毁 | **`sz=2 → 2`，两层原样保留** ✅ |
| 连续点 3 次图标 | — | 栈不增长，**不会堆出多个首页实例** ✅ |
| 二级页按返回 | — | 回到首页（语义正常）✅ |
| 从桌面图标正常打开 | ✅ | ✅ |

第二层这次用的是**爱奇艺自己的二维码登录页**（卸载重装后登录态没了，首页自动拉起登录页），
比之前拿「设置」页模拟更贴近真实场景。
截图：`dist/iqfix-1-relaunch.png`、`dist/iqfix-2-home.png`。

## 代价（已发生，需知悉）

1. **爱奇艺已卸载重装 → 需要重新登录**（登录态与播放记录随卸载清空）。
2. **官方升级会覆盖补丁**：爱奇艺一更新就回到原样，需要重跑一次补丁。

## 升级后怎么重做

```bash
cd D:/tmp/iqwork
python iqpatch.py <新包.apk> raw.apk        # 自动定位并改字节
zipalign -p -f 4 raw.apk aligned.apk
java -jar $BT/lib/apksigner.jar sign --ks iq.keystore --ks-pass pass:android \
     --key-pass pass:android --ks-key-alias iq \
     --v1-signing-enabled true --v2-signing-enabled true --out final.apk aligned.apk
adb uninstall com.gitvvideo.skyworthopen && adb install final.apk
```

> 新包的 manifest 布局会变（偏移不一样），所以 `iqpatch.py` 是**每次重新扫描定位**的，
> 不能硬编码偏移；命中处数不唯一时脚本会主动中止，避免改错。

---

# 八、⚠️ 重大订正：真凶不是 launchMode —— 播放页是「一离开 App 就被销毁」

收到「没起作用」的反馈后重新实测，**前七章的因果链是错的**。以下是第二轮（真机、已登录、真实播放）的结论。

## 8.1 真实播放页长什么样（关键）

真机点开影片后，把栈打出来：

```
$ dumpsys activity activities | grep -E 'Task\{|Hist #'
* Task{533f3f4 #3120 visible=true ... A=10080:com.gitvvideo.skyworthopen ... sz=2}
    affinity=10080:com.gitvvideo.skyworthopen          ← 就一个任务，主任务
* Hist #1: ActivityRecord{9e9796c u0 com.gala.video.plugincenter.component.InstrActivityProxy0 t3120}
    Intent { act=com.gala.video.plugin.epg@#@#com.gala.video.app.player.PlayerActivity
             cat=[com.gala.video.app.player.PlayerActivity] flg=0x10000000
             cmp=com.gitvvideo.skyworthopen/com.gala.video.plugincenter.component.InstrActivityProxy0
             (has extras) }
* Hist #0: ActivityRecord{5ef6b48 u0 com.gala.video.app.epg.HomeActivity t3120}
```

两件事一眼可见：

1. **真实播放页不是宿主清单里那个 `com.gala.video.app.player.PlayerActivity`**，
   而是**插件里的 Activity**，由宿主声明的**代理 Activity** `InstrActivityProxy0`
   （`standard`，affinity = 包名，落在**主任务**内）承载。
   宿主清单里那条 `PlayerActivity`（singleTask）是给客户端 SDK 用的另一套，不是 TV 界面的入口。
2. 所以第二、六章那套「singleTask 会销毁它之上的 Activity」的推理**根本没轮到上场**。

## 8.2 决定性实测：销毁发生在按 HOME 的那一瞬间

播放中（`dist/iqre-12-playing.png`，字幕在走、画面正常），连续采样：

| 时刻 | 爱奇艺任务栈 |
|---|---|
| 播放中 0s / 20s / 40s | `sz=2`（首页 + 播放页代理），**稳定** |
| **按 HOME 后 1 秒** | **`sz=1`** ← 播放页已经没了 |
| 按 HOME 后 3s / 10s | `sz=1` |
| 再点图标重进 | `sz=1`，看到首页 |

**播放页不是"重进时被清"，而是"按 HOME 那一刻就没"**（1 秒内）。同一手法复现 **5 次**，次次如此。

推论：
- 进爱奇艺时播放页**早已不存在** → 首页的 `launchMode` 是 singleTask 还是 standard **都不影响结果**；
- 第七章那个补丁因此是**惰性的**：装着无害，但对此现象零作用（这也解释了「没起作用」）。

## 8.3 为什么没法继续往下改

| 思路 | 结果 |
|---|---|
| 改宿主 `HomeActivity` 的 launchMode | ❌ 已试，无关（播放页在 HOME 时已销毁）|
| 改插件里 `PlayerActivity` 的 launchMode | ❌ 插件有 **MD5 校验**：`shared_prefs/plugins_config.xml` 里 `"md5":"5c4786cb8262f1a3e418f62e900f13cf"`，另有逐 so 的 `plugin_so_verify.xml`，改完会被判非法并重新下载 |
| 找「后台播放」开关 | ❌ 没有。`player_config.xml` 里唯一沾边的 `smallWindow`，改成 true 后**启动即被服务端下发配置覆盖回 false**，且无效 |
| 画中画（PiP）| ❌ 宿主清单无 `supportsPictureInPicture`，盒子也没有该特性 |
| 让桌面 App 把播放页"拉"回来 | ❌ 记录已销毁；桌面无权限读别人任务栈；盒子后门 `usrchan` 只给 shell 组 |
| 外部直接拉起宿主清单里的 `PlayerActivity` | ❌ exported 但不吃空 intent，一拉就自退 |

**结论：看剧中途回桌面再回来接着看，这台盒子上的爱奇艺做不到。**
销毁逻辑在**服务端下发、MD5 校验的插件**里，属于不该（也难以安全）触碰的范围。

## 8.4 实际可用的替代

1. 回桌面后再进爱奇艺 → 首页第一行就有「**你正在追 / 继续观看**」入口，一键回到该剧（并接着上次进度）。
2. 别按系统「最近任务」里的「**一键优化**」—— 那个才是真清后台。
3. 想彻底避免中断：看剧期间不按 HOME（本机没有别的"多任务保播放"机制）。

## 8.5 这次踩的坑（写给以后的自己）

**仿真组件必须与被仿真的组件同机制。**
第六章那组 G1/G2 实验里，第二层是我临时写的**宿主 Activity**（不会自我销毁），
而真实播放页是「**插件 + 宿主代理**」，生命周期完全不同 —— 用它得出的结论直接是错的，
还害得用户白卸装一次。以后凡涉及"代理 / 插件 / 容器"类组件，
必须先把**真机上真实组件**的任务栈打出来，再下结论。
（另有小坑：`AlertDialog` 之外的 `dumpsys` 统计不要用 `grep -c` 数实例，
同一条 ActivityRecord 在输出里会出现多次；只认 `Hist #` 列表。）
