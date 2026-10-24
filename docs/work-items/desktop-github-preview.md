# PC修复源码、Draft PR与预发布交付

2026-10-24用户明确选择：上传源码、开Draft PR，并提供PC预发布安装包。本项负责这三项实际交付，不合并PR、不提升稳定版、不修改Android源码或签名、不操作真机。

## 基线与所有权

- 独立PC参考：desktop-keepalive /fix/desktop-receive-continuity，产品c8c0cb688815bcf58888173a31abd2da460d3917，交接c871a27，版本1.3.1-test.21-vpn.2，干净。本地包/回退/听感待验收状态保持。
- 远端main已前进到ea2dfe802b05de2ba988363aa06c98be5c0027f4（近期Gradle缓存/发布CI更新）；不得以旧PC分支覆盖这些改动。已有PC Draft PR#31基于旧test17，本项在当前main上接入完整PC候选，关联#31/#55，避免引入Android工作分支的差异。
- 隔离工作树publish/desktop-receive-preview从ea2dfe8创建，起点干净，不移动或覆盖其他工作树。使用已授权GitHub连接器上传源码。
- 仅认领desktop/的已提交候选内容、所需原生测试助手、PC专属CI/发布入口与说明/项目记忆。根容器、private、旧产物、其他任务dirty source均不作为上传源。

## 方案与验收

1. 将已提交PC模块精确接入当前main，保存来源清单；核对Android树及其他产品路径未变。保持当前main稳定发布工作流和Gradle缓存，不复制旧工作流覆盖。
2. 使用PC专属预发布请求及工作流：同仓PR、明确publish请求、Windows完整check和干净源码构建均通过后，由CI发布版本v1.3.1-test.21-vpn.2，prerelease=true/latest=false。只包含Windows包、SHA256和准确SOURCE_COMMIT，不触发APK签名或安装。
3. 本地复验导入及CI脚本/版本/安全边界；上传聚焦分支，开Draft PR并关联。确认远端SHA、CI结果、发布资产及下载校验，不把本地c8c0cb6包冒充新远端CI产物。
4. 说明冷启动仍可能裁掉开头的一小段音频；本地模型和短测不是手机/平板听感或过夜验收。整个音质问题保持open；不上传设备诊断或原始音频。
5. 完成后把GitHub链接、版本/来源/校验与未来PC继续开发入口写入状态/交接/目录导航；独立旧仓保留为本地历史，避免今后再次只在无remote的副本里修正式产品。

本地实现/验证节点：177个Desktop文件的Git blob均与导入参考相同，保留main上gradlew的可执行位；Android树8bae6c11aebf0c2cb9a1e53a27c36ca4380fa91d、稳定发布和Android工作流未改。本地完整Desktop检查通过；PC请求/版本、包路径/元数据、SHA篡改、fork和手动发布边界、标签不可改写的保护测试通过。发布流程保留main现有Gradle缓存，先检查/构建/校验，再上传并下载复核Draft附件，最后公开预发布。

状态：本地接入及发布保护验证完成，准备通过连接器上传分支并开Draft PR；远端CI和Release仍待实际执行。
