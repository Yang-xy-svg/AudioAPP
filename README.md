# OpenMic 手机录音库

这是 `ESP32-OpenMic` 录音卡的 Android 接收端。待机时手机通过 BLE 连接录音卡查看状态；需要查询或传输录音时，应用经 BLE 唤醒录音卡的 Wi-Fi，再通过 HTTP 读取 `/files` 页面、下载 WAV 原文件并播放。

## 已实现

- 手动输入 IP、`.local` 主机名或完整 HTTP 地址。
- 通过 Android 局域网服务发现自动查找 OpenMic，并验证 `/files` 页面。
- 查询录音卡 SD 卡中的 `/files`，显示录音文件、时间和大小。
- 蓝牙待机连接：扫描 OpenMic 广播，显示录音、SD 卡、电池和 Wi-Fi 状态，断线自动重连。
- 查询或下载前自动发送 `WIFI_ON`，待录音卡获得 IP 后切换到 HTTP 传输。
- 下载并播放、仅下载、暂停/继续、删除手机副本。
- 下载进度、网络错误提示、已保存录音的离线播放。
- WAV 文件头校验，避免把错误页面保存成录音。

## 使用

1. 停止录音卡上的录音。固件默认会在录音期间关闭 Wi-Fi。
2. 打开手机蓝牙并授予蓝牙权限，点击“蓝牙待机连接”。
3. 让手机和录音卡连接同一个 Wi-Fi。
3. 在浏览器中确认 `http://录音卡地址/` 能打开。
4. 打开应用，输入录音卡地址，例如 `192.168.1.50` 或 `mic.local`。
5. 点击“连接”，App 会立即查询 SD 卡；以后可随时点击“查询 SD 卡”刷新列表。
6. 选择录音“下载并播放”。

若已通过蓝牙连接，点击“查询 SD 卡”时 App 会先写 `WIFI_ON`，等状态特征上报 IP 后自动连接该 IP，无需手工输入地址。

录音保存在应用私有目录。删除应用数据或卸载应用会一并删除这些下载副本；录音卡 SD 卡上的原文件不受影响。

## 构建

项目使用 Android SDK 36.1、JDK 22、Gradle 9.4.1。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-22'
.\gradlew.bat --gradle-user-home "$PWD\.gradle-user" assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 当前限制

- 这是“录完后下载播放”，不是实时麦克风流。
- 录音期间固件会关闭 Wi-Fi；需要通过网页设置 `wifi.record`/`wifirecord` 才能边录边联网。
- 固件默认输出 44.1 kHz、16 位、双声道 PCM WAV。系统播放器对 24 位 WAV 的支持取决于手机型号。
- 文件保存在应用私有目录，暂未接入系统音乐库或分享菜单。
