#!/usr/bin/env bash
# Ubuntu 22.04: install runtime dependencies; no API credentials are requested.
set -euo pipefail
[[ "$EUID" == 0 ]] || { echo 'Run as root.' >&2; exit 1; }
source /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 22.04 ]] || { echo 'Requires Ubuntu 22.04.' >&2; exit 1; }
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a
apt-get update -qq
apt-get install -y openjdk-21-jre-headless nginx postgresql-14 postgresql-server-dev-14 build-essential curl ca-certificates

if [[ ! -f /usr/share/postgresql/14/extension/vector.control ]]; then
    build_dir=$(mktemp -d /tmp/zhiyu-pgvector.XXXXXXXX)
    trap 'rm -rf -- "$build_dir"' EXIT
    curl --fail --location --retry 3 --max-time 120 \
        https://codeload.github.com/pgvector/pgvector/tar.gz/refs/tags/v0.8.1 \
        --output "$build_dir/vector.tar.gz"
    echo "a9094dfb85ccdde3cbb295f1086d4c71a20db1d26bf1d6c39f07a7d164033eb4  $build_dir/vector.tar.gz" | sha256sum --check
    tar -xzf "$build_dir/vector.tar.gz" -C "$build_dir"
    make -C "$build_dir/pgvector-0.8.1" PG_CONFIG=/usr/lib/postgresql/14/bin/pg_config -j2
    make -C "$build_dir/pgvector-0.8.1" PG_CONFIG=/usr/lib/postgresql/14/bin/pg_config install
fi
systemctl enable --now postgresql
id zhiyu >/dev/null 2>&1 || useradd --system --home-dir /var/lib/zhiyu --shell /usr/sbin/nologin zhiyu
install -d -m 750 -o root -g zhiyu /etc/zhiyu
install -d -m 755 /opt/zhiyu/releases

# Generate the database password on the server, without printing it or putting
# it in a process argument. Never overwrite an existing environment file.
python3 - <<'PY'
import os
import pathlib
import pwd
import secrets
import subprocess

env = pathlib.Path('/etc/zhiyu/server.env')
if env.exists():
    print('Existing server environment preserved.')
else:
    def sql(query):
        result = subprocess.run(['runuser', '-u', 'postgres', '--', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-At'],
                                input=query, text=True, capture_output=True)
        if result.returncode:
            raise SystemExit('Database initialization failed; credential-bearing SQL output suppressed.')
        return result.stdout.strip()

    if sql("SELECT 1 FROM pg_roles WHERE rolname='zhiyu';") or sql("SELECT 1 FROM pg_database WHERE datname='zhiyu';"):
        raise SystemExit('Existing zhiyu database/role found without server.env; refusing to replace credentials.')
    password = secrets.token_hex(32)
    sql("CREATE ROLE zhiyu LOGIN PASSWORD '" + password + "';")
    sql('CREATE DATABASE zhiyu OWNER zhiyu;')
    uid = pwd.getpwnam('zhiyu')
    fd = os.open(env, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o640)
    os.fchown(fd, 0, uid.pw_gid)
    with os.fdopen(fd, 'w') as stream:
        stream.write('POSTGRES_HOST=127.0.0.1\nPOSTGRES_DB=zhiyu\nPOSTGRES_USER=zhiyu\nPOSTGRES_PASSWORD=' + password + '\n')
        stream.write('# Fill cloud settings privately on the server, then restart zhiyu.\n')
        stream.write('ARK_API_KEY=\nARK_MODEL=\nARK_VISION_MODEL=\nVOLC_SPEECH_API_KEY=\nVOLC_SPEECH_APP_ID=\nVOLC_SPEECH_ACCESS_TOKEN=\n')
    print('Created private server environment; cloud providers remain unconfigured.')
PY
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -d zhiyu -c 'CREATE EXTENSION IF NOT EXISTS vector;'
