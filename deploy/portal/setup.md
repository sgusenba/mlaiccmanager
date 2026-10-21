# Running the registration portal on a Linux machine

How to put the registration portal (`registration-portal/`) on a Linux machine (Debian or Ubuntu; a rented server or one of the club's own) and keep it running. The portal is one jar, `mlaic-portal.jar`, with the pages inside. It is published with the scripts of this folder on the `portal-latest` release of this repository.

What you end up with:

```
internet ──443──> Caddy (HTTPS, Let's Encrypt, security headers)
                    └──> 127.0.0.1:8080  mlaic-portal (Java, systemd sandbox, no other network)
                                           └── /var/lib/mlaic-portal   portal.json, meets/   (0700)
before every start:  mlaic-portal-update ──> newer build on portal-latest? backup, then install it
nightly:             mlaic-portal-backup ── tar | age ──> /var/backups/mlaic-portal (──> elsewhere)
```

The MLAICC Manager is **never** put on the internet; the portal is the only part that is. The two exchange files only.

| Path | What |
|---|---|
| `/opt/mlaic-portal/` | `mlaic-portal.jar` and the scripts (root-owned, read-only for the service) |
| `/etc/mlaic-portal/portal.env` | settings ([`portal.env.example`](portal.env.example)) |
| `/etc/mlaic-portal/backup-recipients.txt` | the age **public** key(s) of the backups |
| `/var/lib/mlaic-portal/` | the data: personal data, 0700, user `mlaic-portal` |
| `/var/backups/mlaic-portal/` | encrypted backups, 30 days |
| `/var/log/caddy/mlaic-portal-access.log` | access log, 14 days |

## 1. Before you start

- **The machine**: Debian 12 or Ubuntu 22.04/24.04, 1 GB memory or more, reachable from the internet on ports **80 and 443** (at home: forward both on the router to this machine). SSH with keys only (step 6).
- **A host name** pointing to the machine's public IP address (an *A record*), e.g. `anmeldung.svbadzell.at` as a subdomain of the club's website, or a free DuckDNS name. Caddy needs it for the certificate.
- **A backup key** (step 3).

## 2. Install

On the machine, as root:

```bash
curl -fsSLO https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest/portal-install.sh
sudo bash portal-install.sh --domain anmeldung.svbadzell.at --email info@svbadzell.at --backup-key age1...
```

`portal-install.sh` (read it first, it is short):

1. installs Java (17 or newer, from the distribution) and, for `--domain`, Caddy from its official repository,
2. downloads the jar and the scripts from the release and checks them against `SHA256SUMS`,
3. creates the user `mlaic-portal` and the folders above, writes `/etc/mlaic-portal/portal.env`,
4. installs the services, the 14-day journal retention and, with `--backup-key`, the daily backup,
5. writes `/etc/caddy/Caddyfile` for the host name (an existing one is kept as `Caddyfile.before-mlaic-portal`) and opens 80/443 if `ufw` is on,
6. starts the portal and waits until it answers.

Then create the first organiser account (asks for the password twice):

```bash
sudo /opt/mlaic-portal/portal-cli.sh --add-organiser stefan
```

and log in at `https://<host>/login/`: turn on the authenticator app (*Account*), create the second organiser's account, enter the data controller (SV Bad Zell, address, email) and, if the server is rented, who runs it (e.g. "Hetzner Online GmbH, Gunzenhausen, Germany"); both appear on the privacy page.

Other options: `--base-url https://…` instead of `--domain` when you run your own reverse proxy (it must replace `X-Forwarded-For` with the client's address and set `X-Forwarded-Proto`, as Caddy does by default; the security headers to add are in [`Caddyfile.example`](Caddyfile.example)); `--port`; for a test in your own network without HTTPS `--bind 0.0.0.0 --base-url http://192.168.1.10:8080` (never like that on the internet: passwords would travel unencrypted). Running the script again installs the current scripts and unit files and keeps the data; options given then change the settings, e.g. `--domain` after such a test.

## 3. Backup key (on your own computer)

The backups are encrypted with [age](https://age-encryption.org) to a public key; the private key never goes onto the server.

```bash
age-keygen -o mlaic-portal-backup-key.txt      # prints "Public key: age1..."
```

Keep `mlaic-portal-backup-key.txt` offline, in two places (e.g. the password manager and a USB stick in the club's safe); the second organiser should be able to reach one of them. Without it no backup can be read. The public key (`age1...`) is what `--backup-key` takes; a second organiser's own key can be added the same way (run the installer again with it), then either can restore.

## 4. Backups and the restore drill

Every night `mlaic-portal-backup.timer` packs the data, encrypts it and keeps it in `/var/backups/mlaic-portal` for 30 days; before installing a new build the update check takes one too.

```bash
sudo systemctl start mlaic-portal-backup        # one backup now
journalctl -u mlaic-portal-backup -n 20
systemctl list-timers mlaic-portal-backup.timer # the next nightly run
```

**Copy them elsewhere**: a backup on the same disk does not survive the disk. Set `BACKUP_COPY_COMMAND` in `/etc/mlaic-portal/portal.env` to a command that copies each new backup to another machine or a storage service; it runs as the portal's user and gets the file's path (`{}`, or appended). The files are encrypted, so any storage will do (a NAS, a storage box, S3-compatible object storage); let it delete files older than 30 days. Examples:

```bash
# rclone (configure with: sudo -u mlaic-portal rclone config --config /etc/mlaic-portal/rclone.conf)
BACKUP_COPY_COMMAND="rclone copy --config /etc/mlaic-portal/rclone.conf {} offsite:mlaic-portal"
# scp with a key that may only write there
BACKUP_COPY_COMMAND="scp -i /etc/mlaic-portal/backup-ssh-key -q {} backup@nas.example.org:mlaic-portal/"
```

(Files the command needs must be readable by the user `mlaic-portal`.) Without it you can fetch them yourself, e.g. weekly with `scp`.

**Restore drill, before go-live and once a season.** On your own computer, with the private key, Java 17+, `mlaic-portal.jar` and `portal-restore.sh` from the release:

```bash
bash portal-restore.sh extract mlaic-portal-…-daily.tar.gz.age drill -i mlaic-portal-backup-key.txt
PORTAL_DATA_DIR=drill PORTAL_PORT=8099 java -jar mlaic-portal.jar
```

Open `http://localhost:8099/login/`, log in, check meets, clubs and starters, download the export. Then delete `drill/` and the backup file (personal data).

**Restoring on the server** (data lost or broken): decrypt on your computer (`age -d -i mlaic-portal-backup-key.txt -o b.tar.gz b.tar.gz.age`), copy `b.tar.gz` to the server, then

```bash
sudo /opt/mlaic-portal/portal-restore.sh install b.tar.gz && rm b.tar.gz
```

It stops the portal, keeps the current data as `/var/lib/mlaic-portal.before-restore-<time>` (delete it once all is well), starts it again and waits for it to answer. Changes after the backup are lost: tell the clubs to check their lists.

## 5. Updates

**Before every start** of the portal (at boot, and on every `systemctl start` or `restart`), `mlaic-portal-update.service` runs `portal-update.sh`: it compares the installed jar and scripts with `SHA256SUMS` on the `portal-latest` release and installs what is newer, after checking the checksum, test-running the jar (`--version`) and taking a backup. The old jar is kept as `mlaic-portal.jar.prev`. Without a connection, or if anything goes wrong, the installed version starts: an update never keeps the portal from starting.

```bash
sudo systemctl restart mlaic-portal                 # update to the latest build now (logs everyone out)
journalctl -u mlaic-portal-update -n 20             # what the last check did
java -jar /opt/mlaic-portal/mlaic-portal.jar --version
sudo touch /etc/mlaic-portal/no-auto-update         # freeze the version (e.g. in the days before a deadline)
sudo rm /etc/mlaic-portal/no-auto-update            # update again
sudo /opt/mlaic-portal/portal-update.sh --rollback  # back to the previous jar (and freeze); then restart
```

A restart logs everyone out (sessions are kept in memory): restart for an update outside the busy days before a deadline. Unit files and the Caddyfile are never changed by the update check; when it reports a newer unit file, run `sudo /opt/mlaic-portal/portal-install.sh` (no options needed).

Operating-system updates: turn on `unattended-upgrades` (`sudo apt install unattended-upgrades`) with a reboot window at night, e.g. in `/etc/apt/apt.conf.d/52automatic-reboot`:

```
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:30";
```

Each reboot also checks for a new portal build. Once a month: `sudo apt full-upgrade` and `systemctl --failed`.

## 6. Hardening the machine

- **SSH**: keys only, no root log-in. `/etc/ssh/sshd_config.d/10-hardening.conf`:

  ```
  PasswordAuthentication no
  KbdInteractiveAuthentication no
  PermitRootLogin no
  ```

  then `sudo systemctl reload ssh` (test a second SSH session before closing the first).
- **Firewall**: only 22, 80 and 443 open, e.g. with ufw: `sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw enable`. The portal's own port (8080) listens on 127.0.0.1 only; it must not be reachable from outside.
- Optionally `sudo apt install fail2ban` (its default jail covers SSH).
- Nothing else on the machine that is reachable from the internet, in particular **not the MLAICC Manager**.

Check from another computer:

```bash
curl -sI https://anmeldung.svbadzell.at/login/      # 200, strict-transport-security, content-security-policy
curl -m 5 http://<IP>:8080/api/health               # must time out
```

and run <https://www.ssllabs.com/ssltest/> on the host name: grade **A** expected.

## 7. Monitoring

- **Uptime**: an external check of `https://<host>/api/health` every few minutes with an email alert (any free uptime service; it sees only that URL). `/api/health` answers 503 when the portal cannot read its data.
- **Backups**: `systemctl --failed` and `journalctl -u mlaic-portal-backup`; a new file in `/var/backups/mlaic-portal` (and elsewhere) every day.
- **Disk and memory**: `df -h /` and `free -h` now and then; the data are small, the journal and Caddy logs are capped.
- **Log-ins**: `journalctl -u mlaic-portal | grep -i "failed"` shows failed log-ins with the client address. Many from one address are blocked by the rate limits; many from many addresses: consider closing the registration early and replacing the club codes.

## 8. A season

1. In the Manager: export the meet's rankings and the discipline catalog.
2. Portal *Meets*: create the meet (deadline, deletion date = meet date + 90 days), upload both files, check the offered rankings.
3. *Clubs*: create each club, send its invite link **to the club's contact person only** (email or in person, not in a public group).
4. Open the registration. Clubs enter and change their starters until the deadline; then the sheets are read-only, late entries go through *Starters → Edit list*.
5. *Download*: the file for the Manager's **Import Registrations** page. Import again after late entries; nothing is duplicated.
6. The portal deletes the meet's registrations at the deletion date by itself; backups follow 30 days later.

## 9. Data protection records

**Records of processing** (Art. 30 GDPR), entry to keep with SV Bad Zell's other records:

| | |
|---|---|
| Controller | SV Bad Zell (address, contact as on the privacy page) |
| Purpose | Registration of starters for MLAIC meets organised by the controller; transfer to the competition software |
| Legal basis | Art. 6(1)(b) GDPR (taking part in the competition at the starter's request, via the club); for the club contacts Art. 6(1)(f) |
| Data subjects | Starters; contact persons of the clubs; organisers |
| Data | Starters: name, gender, year of birth, club, country, rankings entered, optionally email. Contacts: name, email. Organisers: user name, password hash. Technical: IP address in logs (14 days) |
| Recipients | The organiser (SV Bad Zell); each club sees its own entries only; if the server is rented: its hoster (processor), and the storage the backups are copied to (encrypted) |
| Third countries | None, if server and backup storage are in the EU/EEA |
| Deletion | Meet date + 90 days automatically; backups 30 days after that; logs 14 days |
| Measures | HTTPS only (HSTS), strict CSP; Argon2id passwords, authenticator app, rate limits; club codes 80 bits, hashed, revocable; each club limited to its own rows; data folder readable by the service only, service sandboxed without network access beyond the proxy; encrypted backups (key offline); access logs; automatic updates |

With a rented server, a data processing agreement with the hoster is needed (most offer one in their customer area); keep a copy with the records.

Still to settle (plan, *Still open*): whether the clubs pass their members' data on as separate controllers (the usual case) or act on SV Bad Zell's behalf; it changes only the privacy notice's wording and the clubs' confirmation.

## 10. Data breach procedure

A breach is any loss of confidentiality, integrity or availability of personal data: e.g. someone gets a club's code or an organiser's password, the server is compromised, a backup key is lost, data are deleted by mistake.

1. **Contain** at once: close the registration (*Meets*), replace or block affected club codes (*Clubs*), change organiser passwords and reset the authenticator app (`portal-cli.sh`), then `sudo systemctl restart mlaic-portal`, which ends every open session (they are kept in memory; a password changed on the server does not reach them otherwise); if the server itself is suspect, take it off the network (`sudo systemctl stop caddy mlaic-portal`, or switch it off).
2. **Assess**: what data, how many people, since when (`journalctl -u mlaic-portal`, the meet's change log, Caddy's access log).
3. **Report** to the Austrian data protection authority (Datenschutzbehörde, <https://www.dsb.gv.at>) **within 72 hours** of becoming aware, unless the breach is unlikely to result in a risk to the people concerned.
4. **Inform the people** (via their clubs) without delay if the risk to them is high.
5. **Record** every breach, reported or not: what happened, effects, measures taken.
6. **Recover**: if needed, set the machine up anew with this runbook and restore the last clean backup (step 4).

## 11. Before go-live

- [ ] The host name points to the machine; `https://<host>/login/` loads with a valid certificate; SSL Labs grade A
- [ ] Port 8080 not reachable from outside; SSH keys only; firewall on
- [ ] Two organiser accounts, both with the authenticator app
- [ ] Data controller (and hoster, if rented) entered; the privacy page shows them
- [ ] Backups run and are copied elsewhere; the restore drill done with one
- [ ] Uptime check on `/api/health` alerting your email
- [ ] The backup private key stored in two places
