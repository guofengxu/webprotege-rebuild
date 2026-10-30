#!/usr/bin/env bash
# 提交闸门的解释器入口。Linux 上通常只有 python3，Windows 上文档要求 python 在 PATH 中。
# 选择放在这里，避免 hooks.json 写死一个本机不存在的命令。命令不存在时钩子以 127 失败，
# failClosed 会拒绝所有命中的命令，包括本应放行的只读操作。
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
script="$root/.cursor/hooks/commit_gate.py"
if command -v python3 >/dev/null 2>&1; then
  exec python3 "$script"
fi
if command -v python >/dev/null 2>&1; then
  exec python "$script"
fi
printf '%s' '{"permission":"deny","user_message":"提交闸门找不到 python3 或 python。","agent_message":"已拒绝：提交闸门找不到 Python 解释器。不要换一种写法重试。"}'
