#!/usr/bin/env bash
# Runs inside the MySQL container on first startup (empty data volume).
# By the time this script executes, MySQL Docker has already:
#   - Created the root user with MYSQL_ROOT_PASSWORD
#   - Created the 'mucclub' database and 'mucclub' user (via MYSQL_DATABASE / MYSQL_USER env vars)

set -euo pipefail

export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"

sql() {
    mysql -uroot --default-character-set=utf8mb4 "$@"
}

CLUB_DBS=(
    club_lxjb club_lxyx club_56jws club_kab club_muc club_jsjxh
    club_gybmsy club_jwjzxc club_jhctwh club_qhs club_qnjycyzdfwzx
    club_qnsh club_qnzq club_ruxingshe club_sswl club_syryjl
    club_szsc club_tjq club_tjqs club_wdqc club_xygz club_xyldw
    club_xhyqyx club_ylxxs club_yyjt club_yypqq club_ygbl club_zywh
    club_zlzq club_seca club_tsqwd club_qnyq club_wwybwg club_xbsj
    club_amyxyjjs club_ywzjpq club_zmpkqs club_qcpbxh club_hhfps
    club_ckqhs club_xskccz club_ymxcsgys
)

echo "==> [1/3] Populating master database clubs table..."
sql mucclub < /opt/sql/master.sql
echo "    clubs table ready."

echo "==> [2/3] Creating 42 club databases and granting privileges..."
for db in "${CLUB_DBS[@]}"; do
    sql -e "
        CREATE DATABASE IF NOT EXISTS \`${db}\`
            CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        GRANT ALL PRIVILEGES ON \`${db}\`.* TO 'mucclub'@'%';
    "
done
echo "    All databases created."

echo "==> [3/3] Initializing club database schemas..."
TOTAL=${#CLUB_DBS[@]}
INDEX=0
for db in "${CLUB_DBS[@]}"; do
    INDEX=$((INDEX + 1))
    sql "${db}" < /opt/sql/schema.sql
    echo "    [${INDEX}/${TOTAL}] ${db} OK"
done

sql -e "FLUSH PRIVILEGES;"
echo "==> Database initialization complete. ${TOTAL} club databases ready."
