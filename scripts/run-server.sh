#!/usr/bin/env bash
# Runs inside GitHub Actions. Restores world -> starts Paper + tunnel -> backs up -> stops -> starts next run.
set -uo pipefail

RUN_MINUTES="${RUN_MINUTES:-340}"
BACKUP_EVERY_MIN="${BACKUP_EVERY_MIN:-30}"
MC_VERSION="${MC_VERSION:-1.20.4}"
OP_NAME="${OP_NAME:-}"
FIRST_SETUP="${FIRST_SETUP:-true}"
REPO="${GITHUB_REPOSITORY:?must run in GitHub Actions}"
BACKUP_TAG="world-backup"
ROOT="$PWD"
SERVER="$ROOT/server"
FIFO="$ROOT/console.fifo"

mkdir -p "$SERVER/plugins/DonutSMPWorld"

# ── 1. Paper ────────────────────────────────────────────────────────────────
API="https://api.papermc.io/v2/projects/paper/versions/$MC_VERSION"
BUILD=$(curl -fsS "$API/builds" | grep -o '"build":[0-9]*' | tail -1 | cut -d: -f2)
[ -z "$BUILD" ] && { echo "::error::Could not get a Paper build for $MC_VERSION"; exit 1; }
echo "Downloading Paper $MC_VERSION build $BUILD"
curl -fsSL -o "$SERVER/server.jar" "$API/builds/$BUILD/downloads/paper-$MC_VERSION-$BUILD.jar"
echo "eula=true" > "$SERVER/eula.txt"

# ── 2. Backup storage + restore the world ──────────────────────────────────
# Big worlds: external storage (Cloudflare R2 / Backblaze B2 / S3...) via rclone.
# Small worlds: fall back to a GitHub Release (2 GB file limit).
USE_RCLONE=false
if [ -n "${RCLONE_CONFIG_TEXT:-}" ] && [ -n "${BACKUP_REMOTE:-}" ]; then
  sudo apt-get install -y rclone >/dev/null 2>&1 || { echo "::error::could not install rclone"; exit 1; }
  mkdir -p ~/.config/rclone && printf '%s\n' "$RCLONE_CONFIG_TEXT" > ~/.config/rclone/rclone.conf
  USE_RCLONE=true
  echo "Backups: rclone -> $BACKUP_REMOTE"
else
  echo "::warning::No BACKUP_REMOTE/RCLONE_CONFIG set - using GitHub Releases for backups (2 GB max)."
fi

RESTORED=false
if [ "$USE_RCLONE" = true ]; then
  if rclone lsf "$BACKUP_REMOTE" --max-depth 1 2>/dev/null | grep -q '^world/'; then
    echo "Restoring world from $BACKUP_REMOTE (can take a while for big worlds)..."
    rclone copy "$BACKUP_REMOTE" "$SERVER" --transfers ${RCLONE_PAR:-32} --checkers ${RCLONE_PAR:-32} \
      --exclude "plugins/DonutSMPWorld/config.yml" --exclude "plugins/DonutSMPWorld/schematics/**" && RESTORED=true
  else
    echo "No backup found - this is a brand new world."
  fi
elif gh release download "$BACKUP_TAG" -p world.tar.gz -D /tmp --clobber --repo "$REPO" 2>/dev/null; then
  echo "Restoring world from backup..."
  tar xzf /tmp/world.tar.gz -C "$SERVER" && RESTORED=true
  rm -f /tmp/world.tar.gz
else
  echo "No backup found - this is a brand new world."
fi

# ── 3. Plugin + config from the repo (applied AFTER restore so repo edits win) ─
# ── 2b. Import your own files ─────────────────────────────────────────────
# import/        : .tar.gz / .tgz / .zip of an existing server. Unpacked ONLY into a brand-new world.
# server-files/  : copied over the server folder on EVERY start (plugins/, bukkit.yml, permissions.yml, ...).
IMPORTED=false
if [ "$RESTORED" = false ] && [ -d import ]; then
  for f in import/*.tar.gz import/*.tgz import/*.zip; do
    [ -f "$f" ] || continue
    echo "Importing $f ..."
    case "$f" in
      *.zip) unzip -q -o "$f" -d "$SERVER" -x server.jar eula.txt 'archive-*' && IMPORTED=true ;;
      *)     tar xzf "$f" -C "$SERVER" --exclude=server.jar --exclude=eula.txt --exclude='archive-*' && IMPORTED=true ;;
    esac
  done
fi
if [ -d server-files ]; then
  cp -a server-files/. "$SERVER/"
  echo "Copied server-files/ into the server folder"
fi

# The plugin is built from src/. If the build failed or there is no source, the server still starts and uses
# whatever .jar files you put in server-files/plugins/ instead.
JAR=$(ls target/*.jar 2>/dev/null | grep -v original | head -1 || true)
if [ -n "$JAR" ]; then
  cp "$JAR" "$SERVER/plugins/"
  [ -f src/main/resources/config.yml ] && cp src/main/resources/config.yml "$SERVER/plugins/DonutSMPWorld/config.yml"
else
  echo "::warning::No built plugin jar found - starting with only the jars in server-files/plugins/."
fi
cp server-config/server.properties "$SERVER/server.properties"

# ── 4. Tunnel so players can connect (playit.gg) ───────────────────────────
if [ -n "${PLAYIT_SECRET:-}" ]; then
  curl -fsSL -o playit https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-amd64
  chmod +x playit
  nohup ./playit -s --secret "$PLAYIT_SECRET" start > playit.log 2>&1 &
  echo "playit tunnel agent started"
else
  echo "::warning::PLAYIT_SECRET is not set - nobody can connect from outside GitHub."
fi

# ── 5. Memory + CPU tuning ─────────────────────────────────────────────────
CORES=$(nproc)
RCLONE_PAR=$(( CORES >= 48 ? 64 : 32 ))
TOTAL_MB=$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo)
MAX_MB=$(( TOTAL_MB * 85 / 100 ))
WANT_MB=$(( ${MC_RAM_GB:-0} * 1024 ))
if [ "$WANT_MB" -le 0 ]; then
  MEM_MB=$(( TOTAL_MB * 70 / 100 ))
elif [ "$WANT_MB" -gt "$MAX_MB" ]; then
  MEM_MB=$MAX_MB
  echo "::warning::Asked for ${MC_RAM_GB}GB but this runner only has $((TOTAL_MB/1024))GB. Using $((MEM_MB/1024))GB. Set RUNNER_LABEL to a larger runner."
else
  MEM_MB=$WANT_MB
fi
echo "Runner: ${CORES} cores, ${TOTAL_MB}MB RAM -> server heap ${MEM_MB}MB"
TARGET_CORES=64
if [ "$CORES" -lt "$TARGET_CORES" ]; then
  echo "::warning::Expected a ${TARGET_CORES}-core runner but this one has ${CORES} cores. Create a 64-core larger runner named mc-64core (or set RUNNER_LABEL)."
fi

# Huge heaps pause badly under G1 - use ZGC (low-pause) from 24GB up.
if [ "$MEM_MB" -ge 24000 ]; then
  GC_FLAGS="-XX:+UseZGC -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:ParallelGCThreads=$(( CORES * 3 / 4 )) -XX:ConcGCThreads=$(( CORES >= 32 ? CORES / 8 : 2 ))"
else
  GC_FLAGS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 -XX:SurvivorRatio=32 -XX:MaxTenuringThreshold=1 -XX:ParallelGCThreads=$(( CORES > 16 ? 16 : CORES ))"
fi

# Let Paper's chunk system use (almost) every core.
WORKERS=$(( CORES > 4 ? CORES - 2 : 2 ))
IO_THREADS=$(( CORES >= 48 ? 16 : (CORES >= 16 ? 8 : 4) ))
echo "Paper chunk worker threads: $WORKERS, IO threads: $IO_THREADS"
( cd "$SERVER" && java -jar server.jar --initSettings --nogui >/dev/null 2>&1 ) || true
PG="$SERVER/config/paper-global.yml"
if [ -f "$PG" ]; then
  sed -i -E "s/^( *worker-threads:).*/\1 $WORKERS/; s/^( *io-threads:).*/\1 $IO_THREADS/; s/^( *gen-parallelism:).*/\1 true/" "$PG"
  echo "Tuned $PG"
else
  echo "::warning::paper-global.yml was not generated; Paper will pick thread counts automatically."
fi

# ── 6. Start the server, with a FIFO so we can send console commands ───────
rm -f "$FIFO"; mkfifo "$FIFO"
exec 3<>"$FIFO"
cmd() { echo "$*" >&3; }

( cd "$SERVER" && exec java -Xms${MEM_MB}M -Xmx${MEM_MB}M $GC_FLAGS \
    -jar server.jar nogui <&3 > "$ROOT/server.log" 2>&1 ) &
SERVER_PID=$!
tail -n +1 -F "$ROOT/server.log" &

for _ in $(seq 1 120); do
  grep -q 'Done (' "$ROOT/server.log" 2>/dev/null && break
  kill -0 $SERVER_PID 2>/dev/null || { echo "::error::Server crashed on startup"; exit 1; }
  sleep 5
done

# ── helpers ────────────────────────────────────────────────────────────────
backup() {   # backup true|false  (true = server is running)
  local live="$1"
  if [ "$live" = true ]; then cmd "save-off"; cmd "save-all flush"; sleep 20; fi

  if [ "$USE_RCLONE" = true ]; then
    # Incremental: only changed files are uploaded, so this stays fast even for a ~100 GB world.
    for d in world world_nether world_the_end plugins config; do
      [ -d "$SERVER/$d" ] || continue
      EXCL=()
      [ "$d" = plugins ] && EXCL=(--exclude "*.jar" --exclude "DonutSMPWorld/config.yml" --exclude "DonutSMPWorld/schematics/**")
      rclone sync "$SERVER/$d" "$BACKUP_REMOTE/$d" --transfers ${RCLONE_PAR:-32} --checkers ${RCLONE_PAR:-32} \
        "${EXCL[@]}" \
        || echo "::warning::rclone sync of $d FAILED"
    done
    for f in ops.json whitelist.json banned-players.json banned-ips.json usercache.json bukkit.yml spigot.yml commands.yml help.yml permissions.yml; do
      [ -f "$SERVER/$f" ] && rclone copyto "$SERVER/$f" "$BACKUP_REMOTE/$f"
    done
    [ "$live" = true ] && cmd "save-on"
    echo "Backup synced to $BACKUP_REMOTE"
    return
  fi

  ( cd "$SERVER" && tar czf /tmp/world.tar.gz --ignore-failed-read \
      --exclude='plugins/*.jar' --exclude=plugins/DonutSMPWorld/config.yml --exclude=plugins/DonutSMPWorld/schematics \
      world world_nether world_the_end plugins config \
      ops.json whitelist.json banned-players.json banned-ips.json usercache.json \
      bukkit.yml spigot.yml commands.yml help.yml permissions.yml 2>/dev/null )
  [ "$live" = true ] && cmd "save-on"
  gh release view "$BACKUP_TAG" --repo "$REPO" >/dev/null 2>&1 || \
    gh release create "$BACKUP_TAG" --repo "$REPO" --title "World backup" --notes "Automatic world backup. Do not delete."
  if gh release upload "$BACKUP_TAG" /tmp/world.tar.gz --repo "$REPO" --clobber; then
    echo "Backup uploaded ($(du -h /tmp/world.tar.gz | cut -f1))"
  else
    echo "::warning::Backup upload FAILED (over 2 GB? set up rclone backups)"
  fi
  rm -f /tmp/world.tar.gz
}

[ -n "$OP_NAME" ] && cmd "op $OP_NAME"

# ── 7. First-time world setup ──────────────────────────────────────────────
if [ "$RESTORED" = false ] && [ "$IMPORTED" = false ] && [ "$FIRST_SETUP" = "true" ] && [ -n "$JAR" ]; then
  echo "First run: generating bases, schematics and geodes..."
  cmd "generatebases";    sleep 60
  cmd "placeschematics";  sleep 300
  cmd "generategeodes";   sleep 30
  backup true
fi

# ── 8. Main loop ───────────────────────────────────────────────────────────
END=$(( $(date +%s) + RUN_MINUTES*60 ))
NEXT_BACKUP=$(( $(date +%s) + BACKUP_EVERY_MIN*60 ))
WARNED=""
REACHED_END=false
while kill -0 $SERVER_PID 2>/dev/null; do
  NOW=$(date +%s)
  if [ $NOW -ge $END ]; then REACHED_END=true; break; fi
  if [ $NOW -ge $NEXT_BACKUP ]; then backup true; NEXT_BACKUP=$(( $(date +%s) + BACKUP_EVERY_MIN*60 )); fi
  if [ -z "$WARNED" ] && [ $NOW -ge $((END-300)) ]; then cmd "say Scheduled restart in 5 minutes. Back in about a minute."; WARNED=1; fi
  sleep 15
done

# ── 9. Shut down cleanly + final backup ────────────────────────────────────
if kill -0 $SERVER_PID 2>/dev/null; then
  cmd "stop"
  for _ in $(seq 1 24); do kill -0 $SERVER_PID 2>/dev/null || break; sleep 5; done
  kill $SERVER_PID 2>/dev/null || true
fi
backup false

# ── 10. Start the next run (only for a scheduled restart, NOT if you /stop'd it) ─
if [ "$REACHED_END" = true ]; then
  echo "Starting the next run..."
  gh workflow run host.yml --repo "$REPO" --ref "${GITHUB_REF_NAME:-main}" -f first_time_setup=false
else
  echo "Server stopped before the scheduled end - not restarting. Run the workflow again to start it."
fi
