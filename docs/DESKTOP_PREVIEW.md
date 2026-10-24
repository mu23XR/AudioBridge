# PC预发布与源码继续开发

PC源码现在位于AudioBridge主仓的desktop/模块，上传分支publish/desktop-receive-preview基于main@ea2dfe8。原独立本地仓的模块树35b82e9fb798a977f93b8d20fdddb6ab42ba1903（产品参考c8c0cb6）是本次导入参考；原本地包保持原来源，不改称远端CI包。

根.github/workflows/desktop-windows.yml保留main现有Gradle缓存设置，并执行完整check、生成版本手册、构建干净Windows ZIP，再核对包内preview版本和主JAR。scripts/desktop_preview.py准备只含Windows包、来源、校验和说明的发布目录；原生测试助手默认从PATH解析gcc，不依赖某台开发电脑。

docs/releases/desktop-preview.json是明确的PC发布请求，版本必须为test预览且与desktop/gradle.properties一致，配套说明位于同目录的<版本>.md。同仓PR且publish=true，或手动运行明确选择publish时，才能进入发布Job。fork的请求不会获得发布权限；稳定发布工作流、Android代码和签名配置保持原样。

只有Windows完整检查/构建及包校验通过，CI才以准确源码SHA创建不可覆盖的v<版本>标签和Draft预发布容器，上传并下载复核全部附件，再公开为prerelease=true/latest=false。已有公开版本不覆盖；恢复同一来源的未完成Draft只能补齐缺少的同名附件，校验失败明确报错。

未来PC产品修复继续在有远端的主仓分支中进行，通过Draft PR/CI交付；旧独立本地仓保留作历史参考。开工先读取实际分支、HEAD、状态，不覆盖另一任务的工作树。CI通过不替代真机听感或过夜验收，音质相关Issue仍需用户场景验证。
