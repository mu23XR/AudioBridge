# test.16 正式版发布工作（#17，#11）

用户授权将当前可用的 test.16 作为正式基线，已知长时间接收和 OEM 强停问题继续跟踪。独立分支 release/test16-stable 从 v1.3.1-test.16 @60137ef 建立；保留本地 test.17 工作区，Android/Desktop 产品源码不变。

修正 release.yml Windows 版本验证截断及缺少 Checkout 步骤的问题；非 stable 标签不运行 Windows/governance；发布依赖 Android/Windows/governance，通过后附带源码 SHA、文件校验和和明确版本说明。永久签名不变，采用更高时间版本号。候选 v1.3.1 未出现在远程 Release 列表，推送前还须核验 tag 未占用。

工具 create_worktree 因当前任务 cwd 在仓库外返回 Not a git repository，因此用 git worktree add 在用户工作区内建立 stable-source；未改变原工作区分支或已有修改。test.13 GitHub tag Release 返回 404，完整 Release 列表无 test.13，本地旧 APK 按用户条件保留。

本地签名已通过前轮核验，私钥不进入此分支。正式发布仍由 GitHub release.yml 执行，不上传本地手签产物。CI/实际发布结果待记录。
