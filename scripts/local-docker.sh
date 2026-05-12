#!/usr/bin/env bash
# ============================================================
# MUC_CLUB 本地 Docker 联调管理脚本
#
# 用法: ./scripts/local-docker.sh <命令> [选项]
#
# 命令速览:
#   up [--build]              检查/构建 JAR + dist，启动全部服务，
#                             等待 DB 初始化和后端就绪后打印访问地址；
#                             --build 强制重新构建 JAR 和 frontend dist
#   down                      停止容器，named volume 保留，数据完整存活
#   restart [svc] [--build]   重启指定服务 (db|backend|frontend) 或全量重启；
#                             --build 在重启前重新构建对应产物
#   status                    docker compose ps + 数据卷列表
#   urls                      打印前端 / 后端 / Swagger / DB 访问地址
#   logs [svc]                实时 tail 日志（不指定则看全部服务）
#   purge                     确认后执行 down -v，删除所有 named volume，
#                             下次 up 时重建 42 个社团库（彻底清零）
#
# down 与 purge 的区别:
#   down  → 仅停止容器，named volume 保留
#           再次 up 后数据库数据和上传文件完全恢复
#   purge → 停止容器 + 删除所有 named volume
#           再次 up 时数据库从空白重新初始化（42 个社团库全部重建）
#
# Docker Compose 服务说明:
#   db       MySQL 8.0，db_data named volume；
#            healthcheck 等 clubs 表有数据才标 healthy，
#            确保 init.sh（建 42 个社团库）执行完毕后后端才启动
#   backend  JRE 21，挂载 backend/target/*.jar + uploads_data；
#            env vars 将 localhost 覆盖为 db 服务名；depends db healthy
#   frontend nginx 1.25，挂载 frontend/dist；
#            代理 /api/ → backend:8081；depends backend healthy
#
# 关联文件:
#   docker-compose.yml            Compose 配置（项目根目录）
#   scripts/docker-init/init.sh   MySQL 容器首次启动时自动执行，
#                                 创建 42 个 club_* 库并初始化 schema
#   scripts/docker-nginx.conf     Docker 专用 nginx 配置
#   .env / .env.example           端口和密码配置（MYSQL_ROOT_PASSWORD 等）
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

COMPOSE_FILE="${PROJECT_DIR}/docker-compose.yml"
ENV_FILE="${PROJECT_DIR}/.env"
ENV_EXAMPLE="${PROJECT_DIR}/.env.example"

JAR_PATH="${PROJECT_DIR}/backend/target/club-management-1.0.0.jar"
DIST_PATH="${PROJECT_DIR}/frontend/dist"

# ── 颜色 ──────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

die()  { printf "${RED}${BOLD}ERROR: %s${NC}\n" "$1" >&2; exit 1; }
info() { printf "${CYAN}==> %s${NC}\n" "$1"; }
ok()   { printf "${GREEN}✓ %s${NC}\n" "$1"; }
warn() { printf "${YELLOW}⚠  %s${NC}\n" "$1"; }
step() { printf "\n${BOLD}%s${NC}\n" "$1"; }

# ── Docker Compose 包装器 ──────────────────────────────────────────────────────
# 无论从哪个目录运行此脚本，均固定使用项目根的 .env 和 docker-compose.yml
compose() {
    local env_args=()
    [[ -f "$ENV_FILE" ]] && env_args=(--env-file "$ENV_FILE")
    docker compose "${env_args[@]}" -f "$COMPOSE_FILE" "$@"
}

# ── 从 .env 读取端口值（用于打印 URL）────────────────────────────────────────
load_ports() {
    FRONTEND_PORT=80
    BACKEND_PORT=8081
    DB_PORT=3306
    if [[ -f "$ENV_FILE" ]]; then
        while IFS='=' read -r key raw_val; do
            [[ "$key" =~ ^[[:space:]]*# || -z "${key// }" ]] && continue
            # 去掉行内注释和首尾引号/空格
            local val="${raw_val%%#*}"
            val="${val#"${val%%[![:space:]]*}"}"
            val="${val%"${val##*[![:space:]]}"}"
            val="${val#\'}" ; val="${val%\'}"
            val="${val#\"}" ; val="${val%\"}"
            case "$key" in
                FRONTEND_PORT) FRONTEND_PORT="$val" ;;
                BACKEND_PORT)  BACKEND_PORT="$val"  ;;
                DB_PORT)       DB_PORT="$val"       ;;
            esac
        done < "$ENV_FILE"
    fi
}

# ── 前置检查 ──────────────────────────────────────────────────────────────────
check_prereqs() {
    command -v docker >/dev/null 2>&1 \
        || die "Docker 未安装。请访问 https://docs.docker.com/get-docker/"
    docker compose version >/dev/null 2>&1 \
        || die "需要 Docker Compose v2。请访问 https://docs.docker.com/compose/install/"
    if [[ ! -f "$ENV_FILE" ]]; then
        warn "未找到 .env，从 .env.example 复制默认配置..."
        cp "$ENV_EXAMPLE" "$ENV_FILE"
        ok "已创建 .env（默认密码：club123456）"
    fi
}

# ── 构建函数 ──────────────────────────────────────────────────────────────────
build_backend() {
    info "构建后端 JAR（使用 Docker JDK 21 容器，避免宿主机 JDK 版本不匹配）..."

    # 优先使用宿主机 mvn；若不存在则在 Maven wrapper 目录里找
    local mvn_home=""
    if command -v mvn >/dev/null 2>&1; then
        local mvn_bin
        mvn_bin=$(command -v mvn)
        mvn_home=$(cd "$(dirname "$mvn_bin")/.." && pwd)
    else
        # 查找 Maven wrapper dist 目录
        mvn_home=$(find "${HOME}/.m2/wrapper/dists" -maxdepth 5 -name "mvn" -type f 2>/dev/null \
                   | head -1 | xargs -I{} sh -c 'cd "$(dirname {})/.."; pwd' 2>/dev/null || true)
    fi

    [[ -n "$mvn_home" ]] || die "未找到 Maven。请安装 Maven 或确保 .mvn/wrapper/maven-wrapper.properties 存在。"

    docker run --rm \
        -v "${PROJECT_DIR}/backend:/app" \
        -v "${HOME}/.m2:/root/.m2" \
        -v "${mvn_home}:/opt/mvn:ro" \
        -w /app \
        eclipse-temurin:21-jdk-jammy \
        sh -c 'export MAVEN_HOME=/opt/mvn && export PATH=$MAVEN_HOME/bin:$PATH && mvn clean package -DskipTests -q'

    [[ -f "$JAR_PATH" ]] || die "Maven 构建成功但未找到 JAR: $JAR_PATH"
    ok "后端 JAR 构建完成"
}

build_frontend() {
    info "构建前端 dist（npm run build）..."
    (cd "${PROJECT_DIR}/frontend" && npm install --prefer-offline --silent && npm run build)
    [[ -d "$DIST_PATH" ]] || die "前端构建成功但未找到 dist: $DIST_PATH"
    ok "前端 dist 构建完成"
}

# ── 等待辅助函数 ──────────────────────────────────────────────────────────────
wait_for_db() {
    info "等待数据库初始化（首次启动约 60~120s，42 个社团库逐一创建）..."
    local timeout=180 elapsed=0
    while [[ $elapsed -lt $timeout ]]; do
        local health
        health=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}starting{{end}}' \
                 muc_club_db 2>/dev/null || echo "unknown")
        if [[ "$health" == "healthy" ]]; then
            ok "数据库就绪"
            return 0
        fi
        printf "."
        sleep 4
        elapsed=$((elapsed + 4))
    done
    echo ""
    warn "数据库健康检查超时（服务可能仍在初始化中，请稍后用 status 确认）"
}

wait_for_backend() {
    info "等待后端启动..."
    load_ports
    local timeout=120 elapsed=0
    while [[ $elapsed -lt $timeout ]]; do
        if curl -sf "http://localhost:${BACKEND_PORT}/api/club/list" >/dev/null 2>&1; then
            ok "后端就绪"
            return 0
        fi
        printf "."
        sleep 3
        elapsed=$((elapsed + 3))
    done
    echo ""
    warn "后端启动超时（可通过 ./scripts/local-docker.sh logs backend 查看原因）"
}

# ── up ────────────────────────────────────────────────────────────────────────
cmd_up() {
    local do_build=false
    for arg in "$@"; do [[ "$arg" == "--build" ]] && do_build=true; done

    check_prereqs

    # 检查/构建 JAR
    if $do_build || [[ ! -f "$JAR_PATH" ]]; then
        build_backend
    fi

    # 检查/构建 frontend dist
    if $do_build || [[ ! -d "$DIST_PATH" || -z "$(ls -A "$DIST_PATH" 2>/dev/null)" ]]; then
        build_frontend
    fi

    step "启动所有服务..."
    compose up -d --remove-orphans

    echo ""
    wait_for_db
    echo ""
    wait_for_backend
    echo ""

    cmd_urls
}

# ── down ──────────────────────────────────────────────────────────────────────
cmd_down() {
    check_prereqs
    info "停止所有容器（named volume 保留，数据完整）..."
    compose down --remove-orphans
    ok "所有容器已停止"
    echo ""
    echo "  数据库数据和上传文件均保留在 named volume 中。"
    echo "  再次运行 up 即可恢复，数据完全一致。"
    echo ""
    echo "  如需彻底清除数据请运行: ./scripts/local-docker.sh purge"
}

# ── restart ───────────────────────────────────────────────────────────────────
cmd_restart() {
    check_prereqs
    local svc="${1:-}"

    case "$svc" in
        db|backend|frontend)
            local build_flag="${2:-}"
            [[ "$svc" == "backend"  && "$build_flag" == "--build" ]] && build_backend
            [[ "$svc" == "frontend" && "$build_flag" == "--build" ]] && build_frontend
            info "重启服务: $svc"
            compose restart "$svc"
            ok "服务 '$svc' 已重启"
            ;;
        *)
            # 全量重启（透传 --build 等参数）
            cmd_down
            shift || true
            cmd_up "$@"
            ;;
    esac
}

# ── status ────────────────────────────────────────────────────────────────────
cmd_status() {
    check_prereqs
    step "容器状态:"
    compose ps
    echo ""
    step "数据卷:"
    # docker compose 的 project name 由目录名决定（muc_club）
    docker volume ls --filter "name=muc_club" \
        --format "  {{.Name}}  [driver: {{.Driver}}]" 2>/dev/null \
        || echo "  （无相关卷，服务未启动或已 purge）"
    echo ""
}

# ── urls ──────────────────────────────────────────────────────────────────────
cmd_urls() {
    load_ports
    local sep="================================================================"
    echo ""
    echo -e "${GREEN}${sep}${NC}"
    echo -e "${GREEN}${BOLD}  MUC_CLUB 本地联调环境访问地址${NC}"
    echo -e "${GREEN}${sep}${NC}"
    echo -e "  前端应用    ${CYAN}http://localhost:${FRONTEND_PORT}${NC}"
    echo -e "  后端 API    ${CYAN}http://localhost:${BACKEND_PORT}/api${NC}"
    echo -e "  Swagger UI  ${CYAN}http://localhost:${BACKEND_PORT}/api/swagger-ui/index.html${NC}"
    echo -e "  数据库      ${CYAN}localhost:${DB_PORT}${NC}  用户: mucclub  密码: mucclub2025"
    echo -e "${GREEN}${sep}${NC}"
    echo -e "  调试令牌    ${CYAN}GET http://localhost:${BACKEND_PORT}/api/auth/dev/token?stuId=<学号>${NC}"
    echo -e "${GREEN}${sep}${NC}"
    echo ""
}

# ── logs ──────────────────────────────────────────────────────────────────────
cmd_logs() {
    check_prereqs
    if [[ $# -gt 0 ]]; then
        compose logs -f --tail=200 "$1"
    else
        compose logs -f --tail=100
    fi
}

# ── purge ─────────────────────────────────────────────────────────────────────
cmd_purge() {
    check_prereqs
    echo ""
    echo -e "${RED}${BOLD}  ╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}${BOLD}  ║                    ⚠  PURGE 警告                        ║${NC}"
    echo -e "${RED}${BOLD}  ╠══════════════════════════════════════════════════════════╣${NC}"
    echo -e "${RED}  ║  此操作将永久删除：                                      ║${NC}"
    echo -e "${RED}  ║    • 所有 Docker 容器                                     ║${NC}"
    echo -e "${RED}  ║    • db_data 卷（数据库全部数据）                         ║${NC}"
    echo -e "${RED}  ║    • uploads_data 卷（所有上传文件）                      ║${NC}"
    echo -e "${RED}  ║                                                           ║${NC}"
    echo -e "${RED}  ║  下次执行 up 时数据库将从零重新初始化。                  ║${NC}"
    echo -e "${RED}  ║                                                           ║${NC}"
    echo -e "${RED}  ║  down 不会删除数据，如只需停服请用 down。                ║${NC}"
    echo -e "${RED}${BOLD}  ╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
    read -rp "  确认永久删除所有数据？输入 yes 继续（其他任意键取消）: " confirm
    echo ""
    if [[ "$confirm" != "yes" ]]; then
        warn "已取消，数据未变动"
        exit 0
    fi
    info "清除所有容器和数据卷..."
    compose down -v --remove-orphans
    ok "Purge 完成，所有数据已清除"
    echo ""
    echo "  运行 './scripts/local-docker.sh up' 重新初始化并启动。"
    echo ""
}

# ── create-test ───────────────────────────────────────────────────────────────
_print_test_account_legend() {
    local sep="----------------------------------------------------------------"
    echo -e "${sep}"
    echo -e "${BOLD}  账号说明（各社团通用，统一密码: password）${NC}"
    echo -e "${sep}"
    printf "  %-14s %-10s %s\n" "学号"        "角色"       "权限说明"
    printf "  %-14s %-10s %s\n" "test_admin"  "系统管理员" "可见全部待审批活动"
    printf "  %-14s %-10s %s\n" "test_chair"  "社长"       "管理成员/部门/活动"
    printf "  %-14s %-10s %s\n" "test_vchair" "副社长"     "协助社长管理"
    printf "  %-14s %-10s %s\n" "test_dept"   "部长"       "管理本部门"
    printf "  %-14s %-10s %s\n" "test_staff"  "干事"       "基础权限"
    echo -e "${sep}"
}

cmd_create_test() {
    check_prereqs

    local db_state
    db_state=$(docker inspect --format='{{.State.Status}}' muc_club_db 2>/dev/null || echo "missing")
    [[ "$db_state" == "running" ]] || die "DB 容器未运行，请先执行 up"

    # --list：查询哪些社团库已有测试账号
    if [[ "${1:-}" == "--list" ]]; then
        step "已创建测试账号的社团："
        echo ""
        # 遍历所有社团库，检查 test_admin 是否存在
        local found=false
        while IFS=$'\t' read -r club_id club_name db_name; do
            local exists
            exists=$(docker exec muc_club_db bash -c \
                "MYSQL_PWD=\${MYSQL_ROOT_PASSWORD} mysql -uroot --default-character-set=utf8mb4 -sNe \
                \"SELECT COUNT(*) FROM ${db_name}.sys_user WHERE stu_id='test_admin';\"" 2>/dev/null || echo 0)
            if [[ "$exists" -gt 0 ]]; then
                printf "  ${GREEN}✓${NC} %-6s  %-20s  %s\n" "id=${club_id}" "${club_name}" "${db_name}"
                found=true
            fi
        done < <(docker exec muc_club_db bash -c \
            "MYSQL_PWD=\${MYSQL_ROOT_PASSWORD} mysql -uroot --default-character-set=utf8mb4 -sNe \
            \"SELECT id, name, db_name FROM mucclub.clubs ORDER BY id;\"" 2>/dev/null)
        echo ""
        $found || warn "尚未创建任何测试账号，运行 create-test 即可"
        $found && _print_test_account_legend
        return 0
    fi

    # 无参数时默认三个社团（覆盖不同类型，便于测试隔离性）
    local -a targets
    if [[ $# -gt 0 ]]; then
        targets=("$@")
    else
        targets=(club_jsjxh club_lxyx club_szsc)
    fi

    # BCrypt hash of "password"
    local PW='$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi'

    echo ""
    local sep="----------------------------------------------------------------"
    local any_failed=false

    for db in "${targets[@]}"; do
        # 查社团名和 id
        local row
        row=$(docker exec muc_club_db bash -c \
            "MYSQL_PWD=\${MYSQL_ROOT_PASSWORD} mysql -uroot --default-character-set=utf8mb4 -sNe \
            \"SELECT id, name FROM mucclub.clubs WHERE db_name='${db}' LIMIT 1;\"" 2>/dev/null)

        if [[ -z "$row" ]]; then
            warn "找不到 db_name='${db}'，已跳过"
            any_failed=true
            continue
        fi

        local club_id club_name
        club_id=$(echo "$row" | awk '{print $1}')
        club_name=$(echo "$row" | cut -f2-)

        info "在「${club_name}」(${db}, clubId=${club_id}) 中创建测试账号..."

        docker exec muc_club_db bash -c \
            "MYSQL_PWD=\${MYSQL_ROOT_PASSWORD} mysql -uroot --default-character-set=utf8mb4 ${db} -e \"
INSERT INTO member (stu_id, name, gender, college, role, password) VALUES
  ('test_chair', '测试社长',   '男', '测试学院', '社长',   '${PW}'),
  ('test_vchair','测试副社长', '女', '测试学院', '副社长', '${PW}'),
  ('test_dept',  '测试部长',   '男', '测试学院', '部长',   '${PW}'),
  ('test_staff', '测试干事',   '女', '测试学院', '干事',   '${PW}')
ON DUPLICATE KEY UPDATE name=VALUES(name), role=VALUES(role);
INSERT INTO sys_user (stu_id, name, role, password) VALUES
  ('test_admin', '测试管理员', '系统管理员', '${PW}')
ON DUPLICATE KEY UPDATE name=VALUES(name), role=VALUES(role);
\"" 2>&1 || { warn "${db} 创建失败，已跳过"; any_failed=true; continue; }

        ok "「${club_name}」账号就绪（统一密码: password）"
        echo "     clubId=${club_id}  →  test_admin / test_chair / test_vchair / test_dept / test_staff"
    done

    echo ""
    _print_test_account_legend
    echo ""

    $any_failed && warn "部分社团创建失败，请检查上方输出"
    return 0
}
usage() {
    echo ""
    echo -e "${BOLD}用法:${NC} $(basename "$0") <命令> [选项]"
    echo ""
    echo -e "${BOLD}命令:${NC}"
    printf "  ${CYAN}%-30s${NC} %s\n" "up [--build]"               "启动全部服务；--build 强制重新构建 JAR + dist"
    printf "  ${CYAN}%-30s${NC} %s\n" "down"                        "停止全部容器，named volume 保留（数据完好）"
    printf "  ${CYAN}%-30s${NC} %s\n" "restart [svc] [--build]"     "重启全部或指定服务 (db|backend|frontend)"
    printf "  ${CYAN}%-30s${NC} %s\n" "status"                      "查看容器状态与数据卷列表"
    printf "  ${CYAN}%-30s${NC} %s\n" "urls"                        "打印所有服务访问地址"
    printf "  ${CYAN}%-30s${NC} %s\n" "logs [svc]"                  "实时查看日志（不指定则看全部服务）"
    printf "  ${CYAN}%-30s${NC} %s\n" "purge"                       "停止容器 + 删除所有数据卷（彻底清零）"
    printf "  ${CYAN}%-30s${NC} %s\n" "create-test [db...]"          "创建测试账号（默认三个社团；可传多个 db_name）"
    printf "  ${CYAN}%-30s${NC} %s\n" "create-test --list"           "查看已创建测试账号的社团列表"
    echo ""
    echo -e "${BOLD}down 与 purge 的区别:${NC}"
    echo "  down  → 容器停止，named volume 保留"
    echo "          再次 up 后数据库/上传文件完全恢复"
    echo "  purge → 容器 + named volume 全部删除"
    echo "          再次 up 时数据库从空白重新初始化（42个社团库全部重建）"
    echo ""
    echo -e "${BOLD}常用示例:${NC}"
    echo "  ./scripts/local-docker.sh up                  # 首次启动"
    echo "  ./scripts/local-docker.sh up --build          # 修改代码后重新构建并启动"
    echo "  ./scripts/local-docker.sh restart backend --build  # 仅重新构建后端并重启"
    echo "  ./scripts/local-docker.sh down                # 停服，保留测试数据"
    echo "  ./scripts/local-docker.sh purge               # 清空数据，重头再来"
    echo "  ./scripts/local-docker.sh logs backend        # 查看后端启动日志"
    echo "  ./scripts/local-docker.sh create-test                        # 默认三个社团"
    echo "  ./scripts/local-docker.sh create-test club_muc club_qhs    # 指定任意社团"
    echo ""
}

# ── 入口 ──────────────────────────────────────────────────────────────────────
case "${1:-}" in
    up)      shift; cmd_up "$@" ;;
    down)    cmd_down ;;
    restart) shift; cmd_restart "$@" ;;
    status)  cmd_status ;;
    urls)    cmd_urls ;;
    logs)        shift; cmd_logs "$@" ;;
    purge)       cmd_purge ;;
    create-test) shift; cmd_create_test "$@" ;;
    -h|--help|help) usage ;;
    "")      usage ;;
    *)       echo -e "${RED}未知命令: $1${NC}" >&2; usage; exit 1 ;;
esac
