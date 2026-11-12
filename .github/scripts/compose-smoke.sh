#!/usr/bin/env bash
# Kiểm tra một stack Docker Compose đang chạy. Cổng, địa chỉ dịch vụ và khóa đều đọc từ chính stack
# (cấu hình compose và biến môi trường của container backend), không ghi lại trong script này.
#
#   compose-smoke.sh <service được mở cổng ra máy chủ, cách nhau bằng dấu phẩy> <tham số docker compose…>
#   compose-smoke.sh backend -f deploy/compose.yaml
set -euo pipefail

allowed_published=$1
shift
compose=(docker compose "$@")

backend_env() { "${compose[@]}" exec -T backend printenv "$1"; }
status_from_backend() { "${compose[@]}" exec -T backend curl -s -o /dev/null -w '%{http_code}' "$@" || true; }

# 1. Chỉ những service được phép mới công bố cổng ra máy chủ.
published=$("${compose[@]}" config --format json |
  jq -r '[.services | to_entries[] | select((.value.ports // []) | length > 0) | .key] | sort | join(",")')
if [ "$published" != "$allowed_published" ]; then
  echo "Service mở cổng ra máy chủ: [$published], chỉ cho phép: [$allowed_published]"
  exit 1
fi

# 2. Backend trả lời qua cổng đã công bố và đọc được dữ liệu từ PostgreSQL.
backend="http://$("${compose[@]}" port backend "$(backend_env PORT)")"
curl -fsS "$backend/healthz" | jq -e '.data.status == "ok"'
curl -fsS "$backend/api/v1/knowledge/articles" |
  jq -e '.meta.status == "DATABASE_RESULTS" and (.data | length) > 0'

# 3. Backend gọi được từng dịch vụ Python bằng địa chỉ và khóa nó được cấu hình.
#    Không gửi khóa: 401. Gửi khóa: dịch vụ nhận (401 là sai khóa, 503 là dịch vụ chưa đặt khóa).
check_service() { # <tiền tố biến môi trường> <đường dẫn> [tham số curl…]
  local name=$1 path=$2 url key without_key with_key
  shift 2
  url=$(backend_env "${name}_SERVICE_URL")
  key=$(backend_env "${name}_SERVICE_KEY")
  without_key=$(status_from_backend "$@" "$url$path")
  with_key=$(status_from_backend "$@" -H "X-Service-Key: $key" "$url$path")
  echo "$name $path: không khóa HTTP $without_key, có khóa HTTP $with_key"
  [ "$without_key" = 401 ] || exit 1
  case "$with_key" in 000 | 401 | 503) exit 1 ;; esac
}
check_service AI /v1/safety/evaluate -X POST -H 'Content-Type: application/json' -d '{"text":"ci"}'
check_service STATS /v1/system/statistics
