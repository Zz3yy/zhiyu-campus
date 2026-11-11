#!/usr/bin/env bash
set -euo pipefail
[[ "$EUID" == 0 ]] || { echo 'Run as root.' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
release=$(realpath -- "${1:?Pass /opt/zhiyu/releases/RELEASE}")
[[ "$release" =~ ^/opt/zhiyu/releases/[a-zA-Z0-9._-]+$ ]] || { echo 'Unexpected release directory.' >&2; exit 1; }
[[ -s "$release/server.jar" && -s "$release/web/index.html" && -s /etc/zhiyu/server.env ]] || { echo 'Release or server environment incomplete.' >&2; exit 1; }
previous=$(readlink -f /opt/zhiyu/current || true)
chown -R root:root "$release"
chmod -R u=rwX,go=rX "$release"
install -m 644 "$script_dir/zhiyu.service" /etc/systemd/system/zhiyu.service
install -m 644 "$script_dir/nginx.conf" /etc/nginx/sites-available/zhiyu
ln -sfn /etc/nginx/sites-available/zhiyu /etc/nginx/sites-enabled/zhiyu
rm -f /etc/nginx/sites-enabled/default
install -m 755 "$script_dir/backup.sh" /usr/local/sbin/zhiyu-backup
install -m 644 "$script_dir/zhiyu-backup.service" /etc/systemd/system/zhiyu-backup.service
install -m 644 "$script_dir/zhiyu-backup.timer" /etc/systemd/system/zhiyu-backup.timer
nginx -t
ln -sfn "$release" /opt/zhiyu/current.next
mv -Tf /opt/zhiyu/current.next /opt/zhiyu/current
systemctl daemon-reload
systemctl enable zhiyu nginx zhiyu-backup.timer
systemctl restart zhiyu
ready=false
for ((i=0; i<60; i++)); do
    if curl --fail --silent --max-time 2 http://127.0.0.1:8080/api/health >/dev/null; then ready=true; break; fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    if [[ -n "$previous" && "$previous" != "$release" ]]; then
        ln -sfn "$previous" /opt/zhiyu/current.next
        mv -Tf /opt/zhiyu/current.next /opt/zhiyu/current
        systemctl restart zhiyu
    fi
    echo 'Application failed readiness; inspect service diagnostics privately.' >&2
    exit 1
fi
systemctl reload-or-restart nginx
systemctl start zhiyu-backup.timer
echo "Activated $release (private HTTP at 127.0.0.1:8088)."
