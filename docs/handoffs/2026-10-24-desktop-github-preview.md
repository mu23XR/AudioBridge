# PC源码与预发布交接 — 2026-10-24

用户授权源码上传、Draft PR和PC预发布。分支publish/desktop-receive-preview基于main@ea2dfe8，在[Draft PR#71](https://github.com/mu23XR/AudioBridge/pull/71)继续；未合并、未提升稳定版。现有#55关联保留。

交付[1.3.1-test.21-vpn.3](https://github.com/mu23XR/AudioBridge/releases/tag/v1.3.1-test.21-vpn.3)准确源码3fc718ab1d6bfe9b64c153ce384434653119c78b，CI40075136207全部成功，6个附件由CI重建且公开前下载校验。公开后的本机独立下载验证通过，6个附件digest、ZIP/TAR包内容/preview版本及源码SHA均吻合，latest稳定版仍为v1.3.1。发布请求现为false；下次发包须递增版本，不覆盖已存在标签/附件。

初次vpn.2发布在REST按tag读取Draft时404失败，空草稿/标签b4d1d1b保留。后续使用GraphQL Draft ID查找，回归覆盖创建、恢复、错误及下载篡改阻止公开。177个初始Desktop blob均匹配本地产品参考c8c0cb6，之后只调整版本属性到vpn.3，接收逻辑未再变化。Android、共享协议、Android及稳定发布工作流未改。

现有本地vpn.2日常程序和所有回退包保留，不自动覆盖为CI重建版。冷启动裁剪、真实手机/平板听感及长时无线验收未完成；不要因发布/CI通过关闭整个音质问题。后续先查实际HEAD/工作区状态、PROJECT_STATE及工作项，再继续产品修复。

公开ZIP校验：bb51c7780549e3c77d26aa1477a40df7047e01bc67689622e35dc70d49864ed3；TAR校验：42f93717dd316e252fc42a0427d5b81f26386b32048257828d672e608ad33e09。本地CI包独立存放于工作空间artifacts/windows/1.3.1-test.21-vpn.3-3fc718a-ci，下载receipt保存在本机发布证据目录。没有覆盖日常PC副本、改变签名或操作真机。
