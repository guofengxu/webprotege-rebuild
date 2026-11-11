# wp-web

WebProtégé 的 Web 端。开发服务器监听 `5173`，并把 `/api` 代理到本机的 wp-app（`http://localhost:8080`）。浏览器只访问 `5173`，会话 Cookie 与 `XSRF-TOKEN` 保持同源。

Node 版本见 `frontend/.nvmrc`（24）。命令都从仓库根目录执行。

## 本地开发

先启动数据库和后端，再启动前端：

```bash
docker compose up -d postgres
./mvnw -pl :wp-app -am spring-boot:run
pnpm -C frontend --filter wp-web run dev
```

浏览器打开 <http://localhost:5173>。

## 端到端测试

首次运行 Playwright 前安装 Chromium。这条命令可以重复执行。

```bash
pnpm -C frontend --filter wp-web exec playwright install chromium
pnpm -C frontend --filter wp-web run e2e
```
