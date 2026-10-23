# 界面图与宣传素材

全部由真实页面渲染，不是设计稿。渲染方式：无头 Chrome 打开
`studio.kldxst.me/?embed=1#<页>`，外层套 390×844 视口与手机边框。

| 文件 | 尺寸 | 用途 |
|---|---|---|
| 01-cover.png | 1080×1440 | 宣传封面 |
| 02-flow.png | 1600×720 | 全流程操作图 |
| 03-overview.png | 1720×1080 | 四页总览 |
| 04..07 | 840×1800 | 设备 / 应用 / 网桥 / 设置单页 |

图中状态是浏览器预览态（未连接、未校准），不是实机截图：
手环连上后由真实帧填充。素材源文件在 `docs/art/`，改完重新渲染即可。

渲染命令：

```
chrome --headless --disable-gpu --no-sandbox --hide-scrollbars \
  --window-size=1080,1440 --virtual-time-budget=14000 \
  --screenshot=01-cover.png file:///D:/Vela/KdSync/KldxstStudio/docs/art/cover.html
```
