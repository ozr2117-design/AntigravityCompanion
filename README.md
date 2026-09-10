# Antigravity 专属移动伴侣 (Android 原生保活客户端)

专为与电脑端 **Antigravity** 协同打造的超轻量（~4MB）Android 原生客户端。

---

## 解决的核心痛点

1. **解决切后台断开**：内置系统级 `ForegroundService`（前台保活服务），息屏 1 小时以上长连接依然在线，防止 Wi-Fi 路由器 NAT 闲置掐断；
2. **彻底消灭“手动重新点连接”**：本地加密保存当前会话地址与 Token，打开 App **直接秒进对话框**，像微信一样随开随聊；
3. **原生锁屏消息通知**：当电脑端任务完成或回复长消息时，通过 Android 系统的 `NotificationManager` 直接在锁屏弹窗并震动提醒（免任何商业 API 资质申请）；
4. **一键扫码绑定**：内置二维码扫描与手动地址输入，支持随时重新配置。

---

## 极速打包出包方式（首推：GitHub Actions 云端 3 分钟自动打包）

为了避免在本地电脑安装几 GB 的庞大 Android Studio，本项目自带了 GitHub Actions 全自动云端编译流水线：

### 步骤：
1. 在 GitHub 上新建一个仓库（例如名为 `AntigravityCompanion`）；
2. 在本地项目目录下执行：
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git remote add origin https://github.com/<你的用户名>/AntigravityCompanion.git
   git push -u origin main
   ```
3. 打开 GitHub 仓库页面，点击上方的 **【Actions】** 标签页；
4. 你会看到名为 `Build Antigravity Companion APK` 的工作流正在自动运行；
5. 大约 **2 ~ 3 分钟** 编译完成后，点击该次运行记录，在最下方的 **Artifacts** 区域即可直接下载编译好的已签名 **`AntigravityCompanion_v1.0.apk`**！
6. 手机下载后直接点击安装即可使用。

---

## 首次使用指南

1. 打开安装好的 **Antigravity 伴侣**；
2. 首次启动会自动进入绑定界面：
   * 点击 **“📷 扫描电脑端二维码绑定”**，对准电脑屏幕上的 Antigravity 伴侣二维码即可；
   * 或者点击 **“✍️ 手动输入”**，填入电脑内网地址（如 `http://192.168.10.135:8281` 或你的 Tailscale 地址）；
3. 绑定成功后，后续无论何时打开 App，**均自动秒进当前对话**，再也不需要掏出手机扫码！
