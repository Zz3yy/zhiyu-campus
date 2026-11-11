#!/usr/bin/env bash
set -euo pipefail
umask 077
install -d -m 700 /var/backups/zhiyu
backup="/var/backups/zhiyu/garden-$(date -u +%Y%m%dT%H%M%SZ).dump"
trap 'rm -f "$backup.partial"' EXIT
runuser -u postgres -- pg_dump --format=custom --dbname=zhiyu > "$backup.partial"
mv "$backup.partial" "$backup"
# Retain seven days of completed application backups on this disk.
find /var/backups/zhiyu -maxdepth 1 -type f -name 'garden-*.dump' -mtime +7 -delete
