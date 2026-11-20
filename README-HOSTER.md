# GitHub auto-hoster for DonutSMP

Runs your Minecraft server on GitHub Actions. Every run: build plugin -> restore world -> start Paper + tunnel ->
back up every 30 min -> after ~5h40m save, stop and start the next run automatically.

## Setup (5 minutes)
1. Create a GitHub repo and push **everything in this folder** to it (`git init`, `git add .`, `git commit`, `git push`).
2. Make a free tunnel so people can join (GitHub runners have no public IP):
   - Go to https://playit.gg, sign up, create a new **agent**, copy its **secret key**.
   - In the playit dashboard add a tunnel: **Minecraft Java**, local port **25565**. It shows the address players use.
3. In your repo: **Settings -> Secrets and variables -> Actions -> New repository secret**
   name `PLAYIT_SECRET`, value = the key from step 2.
4. In your repo: **Settings -> Actions -> General -> Workflow permissions** -> *Read and write permissions*.
5. **Actions tab -> Minecraft Server -> Run workflow.** Put your Minecraft name in `op_name`.
   On a brand-new world it auto-runs `/generatebases`, `/placeschematics`, `/generategeodes` (takes ~7 min).
6. Join with the address from playit.

## 100 GB setup (big RAM + big world)
The free runner only has 16 GB RAM and a 2 GB backup limit, so for 100 GB:
1. **Bigger runner (paid, needs a Team/Enterprise org):** Org Settings -> Actions -> Runners -> *New GitHub-hosted runner*
   -> Ubuntu -> size **64-core / 256 GB RAM**. Name it exactly **`mc-64core`** (the workflow's default label).
   Add it to a runner group that this repo can use. If you named it something else, set the repo variable
   `RUNNER_LABEL` (Settings -> Secrets and variables -> Actions -> **Variables**) to that label.
   Note: if no runner with that label exists, the job just sits queued.
   (Larger-runner minutes are always billed, even on public repos.)
2. **RAM:** `MC_RAM_GB` variable (default **100**). If the runner has less, it uses 85% of what it has and warns you.
   From 24 GB up the script switches to ZGC to avoid long lag pauses.
3. **Big-world backups (rclone):** make a bucket on Cloudflare R2 / Backblaze B2 / S3, run `rclone config` on your PC once,
   then add: secret `RCLONE_CONFIG` = the contents of your `rclone.conf`; variable `BACKUP_REMOTE` = e.g. `r2:my-mc-bucket`.
   Backups become incremental syncs (fast), with no 2 GB limit.
CPU: the workflow targets a **64-core** runner. The script detects the core count, warns if it is below 64, and sets Paper's chunk worker/IO threads (cores minus 2 workers, 16 IO threads), ZGC threads and rclone parallelism to match. Minecraft's main tick is
single-threaded, so more cores speed up chunk generation/loading and `/placeschematics`, not TPS itself.
Heads up: Minecraft doesn't benefit from 100 GB of RAM - 8-16 GB is normally plenty. More just costs more.

## Using it
- Stop hosting: type `/stop` in-game (it will NOT auto-restart), or cancel the run. Cancelling skips the final
  save, so you lose up to 30 minutes. Prefer `/stop`.
- Start again: run the workflow.
- Change plugin settings: edit `src/main/resources/config.yml` or `server-config/server.properties` and push; the next
  restart picks them up. Plugin edits rebuild automatically.
- The world lives in your rclone bucket (or, without rclone, the **Releases** page, tag `world-backup`, 2 GB max).
- Server runs 5h40m, then ~1-2 minutes of downtime while the next run boots and restores.

## Read this first
- **Terms of service:** GitHub's Actions terms are meant for building/testing software. Running a game server there
  may violate them and could get the repo or account restricted. Use at your own risk; a cheap VPS is the safe option.
- **Public vs private repo:** public repos get unlimited Actions minutes but the world backup release is
  downloadable by anyone. Private repos keep it private but only get ~2,000 free minutes/month (~33 hours of server).
- **Limits:** the runner is ephemeral (everything not backed up is lost), has 4 CPU / 16 GB on public repos
  (2 CPU / 7 GB private), and a backup release asset can't exceed 2 GB.
- If playit's command-line options changed, check `./playit --help` and adjust the line in `scripts/run-server.sh`.

## Folder layout and importing your own files
The server folder on the runner looks like a normal Paper server: `world`, `world_nether`, `world_the_end`, `plugins`, `config`,
`libraries`, `versions`, `cache`, `logs`, `server.jar`, `server.properties`, `bukkit.yml`, `commands.yml`, `help.yml`,
`permissions.yml`, `ops.json`, `banned-players.json`, `banned-ips.json`, `eula.txt`. The worlds, plugins (settings, not .jar files),
`config` and those yml/json files are backed up every 30 minutes and restored on the next start.
- **Add plugins:** put `.jar` files in `server-files/plugins/`. If the plugin build from `src/` fails, the server still starts and just uses these jars (the automatic first-time base setup is skipped in that case). Anything else in `server-files/` (like `bukkit.yml`) is copied into the server folder on every start.
- **Import an old server/world:** put its `.tar.gz` / `.tgz` / `.zip` in `import/` (see the note in that folder). It is unpacked once, on a brand-new world.
- `server.properties` and the DonutSMP plugin's `config.yml` always come from this repo, so edit them here.

## Use your own domain (example: play.yourname.net)
You must own the domain (buy one from any registrar). You can't use a domain someone else owns, such as the real DonutSMP's.
Pick ONE way:
- **Easiest (free): SRV record.** In your domain's DNS add `A` record `play-ip` -> the IP of your playit tunnel, and an `SRV` record
  `_minecraft._tcp.play` -> target `play-ip.yourname.net`, port = your tunnel's port (both shown on the playit tunnel page).
  Players then join with just `play.yourname.net`.
- **playit domain feature:** playit.gg -> Account -> Domains -> Add External Domain, add the `NS` record playit shows
  (`ns1.playit-dns.com`) for a subdomain, then open your tunnel and change its domain to that one. Playit may require a paid plan for this.
Playit gives you a fixed address per tunnel, so you set this up once; it keeps working across the 5h40m restarts.
Then type the address into the panel's "Server address" box so it's shown there.

## Web panel (one website, built in)
The panel is part of this repo and publishes itself as a website.
1. Push this repo to GitHub.
2. Repo **Settings -> Pages -> Source: GitHub Actions**.
3. Run **Actions -> Publish Panel Website** once (it also re-runs whenever `dashboard/` changes).
4. Open `https://YOUR-NAME.github.io/YOUR-REPO/`. The repo is filled in for you; paste a GitHub token
   (Actions + Variables read/write) and press Connect.
Buttons: Start Server, Restart Server, Stop Server. "Server size" sets CPU cores and RAM for the next start.
The 64-core machine must exist first (see "100 GB setup" above); the panel cannot create it.
Private repos need a paid GitHub plan for Pages. You can also just open `dashboard/index.html` from your PC.
