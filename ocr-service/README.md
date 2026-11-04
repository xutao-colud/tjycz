# 本地专用OCR服务

小程序的“拍照识题”由Java后端创建持久化任务，再由本服务定位文字区域、版面及题号并分题。手机不直接连接18086；图片只在本机内部传输，不调用云端大模型。服务就绪前会用合成文字页预热方向、版面、检测和识别模型；不使用真实题目预热，不缓存识别结果。首次安装需要联网下载Paddle官方模型权重。

## 安装与启动

在项目根目录运行，使用Python 3.11；本机macOS arm64环境已安装并实测：

```sh
uv venv --python python3 .run/ocr-venv
uv pip install --python .run/ocr-venv/bin/python -r ocr-service/requirements.txt
bash scripts/ocr-local.sh start
bash scripts/ocr-local.sh status
bash scripts/backend-local.sh restart --build
```

Java需17；本机Maven可显式设置`JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home`。若后端未启动，用`start --build`。数据备份后再更新已有后端。OCR的`stop/restart`只管理本脚本记录且准确命令、启动时间匹配的进程，不停止其他进程。

主依赖固定为PaddlePaddle 3.3.1、PaddleOCR 3.7.0、PaddleX 3.7.2；完整安装版本见`requirements-lock.txt`。复用PaddleOCR的方向校正、PP-DocLayout-S、PP-OCRv5_mobile_det与PP-OCRv6_tiny_rec，CPU串行处理；不运行整套文档解析。先按行高、长宽比例筛选常规印刷文字区域，再识别整行，自动提取标题、题干、选项和表格文字；跳过明显的大字手写区域及独立答题演算行。过滤是版面启发式，不是经训练的印刷/手写分类器，混写区域需人工核对。方向分类开启，书页展平及公式文字转换关闭。模型清单与校验值见`model-manifest.json`。

日志在`.run/ocr-service.log`；服务凭据在`.run/ocr.key`，配置在`.run/ocr-config.json`，不要提交、截图或打包这些文件。后端启动脚本会读取本地配置；其他部署可设置`OCR_URL`和`OCR_KEY_FILE`。本地服务仅监听127.0.0.1，两个接口均校验内部凭据；远程部署须增加内部网络访问控制及HTTPS，不能把服务密钥放入小程序。

## 行为与边界

- 单次一页，≤20MB、≤2000万像素；处理时最长边缩至2600像素。EXIF方向和模型方向校正后保存标准页，题框、预览与裁图共用坐标。
- 默认按顶层题号形成整题，小问、图示与选项保留在所属大题内；“附加题”独立分题。OCR把题号与正文数字连读时，结合相邻题号、左侧对齐和版面标题恢复边界，并提示复核，普通小数不直接当题号。阅读材料随对应题目保留，排除页码，图形被题框切到时提醒。未找到可靠题号时保留整页供手工补框/拆分，不自动判对错。
- 文字检测最长边限制为1280像素；标准页仍保留最长边2600像素供裁题及打印，题框统一使用标准页坐标。接口的`questions[].text`自动填入提取出的印刷题干，`group`优先使用印刷章节标题或题干首行（去题号，最多160字）。小程序复用`questionText`和`groupName`存储、展示及校对，导入时沿用校对后的标题和正文，不新增数据库字段；原图、图表和公式继续保留裁图。阅读材料在有明确阅读标题时以共享裁图保留。
- 默认不勾选，不自动入库。老师可以调框、补框、拆题、合并、改文字；只有勾选题目生成错题及裁图，重复请求不重复创建。PDF使用裁图保留图形、公式和原笔迹。
- 账号过期、教师权限撤回或孩子已脱敏后，任务不得继续处理；原图、标准页、裁图和PDF沿用学生/校区及附件权限。家长单独授权当前默认关闭，启用该配置时仍执行原授权规则。
- 原图及打印裁图保留拍照时的笔迹；本版尽量过滤答案转写，不执行擦除。题号字号异常、特殊缩进、无明确标题的共享阅读材料或复杂多栏需人工核对题框。没有跨页关联、自动擦除手写答案、自动透视/弯曲展平或大模型补扫。严重斜拍、弯曲、反光和复杂手写需要重拍或手工校对；不承诺未经真实样本验证的准确率。

检查：`.run/ocr-venv/bin/python -m unittest discover -s ocr-service -p 'test_*.py' -v`；小程序：`node miniprogram/scripts/check-scan.js`。本地完整流程与验证范围见`docs/question-scan-acceptance-20261031.md`。

## 扫描响应优化

后台在扫描任务事务提交后立即唤醒工作线程，并在完成后继续处理排队任务；1500毫秒定时任务仅作兜底。小程序前12次以250毫秒间隔查询，随后回退为1秒，隐藏页面停止查询。整题印刷标题、正文、表格、校对与打印流程保持，PNG使用无损快速压缩。

新模型均由已安装的PaddleOCR/PaddleX 3.7支持，不升级Python依赖。模型文件校验值已更新；原模型缓存保留供回退。官方模型说明：[PP-OCRv6](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv6/PP-OCRv6.en.md)、[轻量版面模型](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.en.md)。
