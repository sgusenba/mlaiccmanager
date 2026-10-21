# Running the registration portal on Oracle Cloud

The runbook for putting the registration portal (`registration-portal/`) on an Oracle Cloud *Always Free* VM and keeping it there. The files it refers to are in this folder; the release bundle (`portal-latest`) carries them as `deploy/`.

What you end up with:

```
internet ──443──> Caddy (HTTPS, Let's Encrypt, security headers)
                    └──> 127.0.0.1:8080  mlaic-portal (Java, systemd sandbox, no other network)
                                           └── /var/lib/mlaic-portal   portal.json, meets/   (0700)
nightly:  mlaic-portal-backup ── tar | age ──> /var/backups/mlaic-portal ──> OCI Object Storage (30 days)
```

The MLAICC Manager is **never** put on the internet; the portal is the only part that is. The two exchange files only.

| Path | What |
|---|---|
| `/opt/mlaic-portal/` | jar, `static/`, the scripts (root-owned, read-only for the service) |
| `/etc/mlaic-portal/portal.env` | settings ([`portal.env.example`](portal.env.example)) |
| `/etc/mlaic-portal/backup-recipients.txt` | the age **public** key(s) of the backups |
| `/var/lib/mlaic-portal/` | the data: personal data, 0700, user `mlaic-portal` |
| `/var/backups/mlaic-portal/` | encrypted local backups, 7 days |
| `/var/log/caddy/mlaic-portal-access.log` | access log, 14 days |

## 1. Account

1. Oracle Cloud account with home region **Frankfurt** (`eu-frankfurt-1`) or **Zurich**: the data stays in the EU/EEA region chosen here.
2. Upgrade to **Pay As You Go**. Always Free resources stay free, and idle Always Free VMs of free-tier accounts can be reclaimed, PAYG ones are not.
3. *Billing → Budgets*: a budget of €1 with an alert to your email, so any charge is noticed at once.
4. Turn on multi-factor authentication for the Oracle console log-in.

## 2. Domain

Pick the public name now (still open in the plan): a subdomain of SV Bad Zell's own domain, e.g. `anmeldung.svbadzell.at`, is recommended; a free DuckDNS name works as a fallback. Once the VM has its IP (step 3), create an **A record** pointing to it. Caddy needs it to get the certificate.

## 3. VM

*Compute → Instances → Create instance*:

- Image: **Canonical Ubuntu 24.04** (aarch64)
- Shape: **VM.Standard.A1.Flex**, 1 OCPU, 6 GB memory (Always Free)
- Networking: a new VCN with a public subnet; *assign a public IPv4 address*
- Boot volume: 50 GB
- SSH key: your public key (`ssh-keygen -t ed25519` if you have none)

Then *Networking → Reserved public IPs*: reserve one and assign it to the VM's VNIC, so the address survives a stop/start. Point the DNS record to it.

## 4. Firewall (two layers)

**OCI security list** of the subnet (*Networking → VCN → Security lists → Default*), ingress rules:

| Source | Protocol | Port | Why |
|---|---|---|---|
| your IP `/32` (or OCI Bastion) | TCP | 22 | SSH |
| `0.0.0.0/0` | TCP | 80 | Let's Encrypt and redirect to HTTPS |
| `0.0.0.0/0` | TCP | 443 | the portal |

Delete the default rule that opens 22 to `0.0.0.0/0`. Nothing else (in particular not 8080).

**On the VM**: Oracle's Ubuntu images come with iptables rules that end in a `REJECT`. Insert 80 and 443 before it:

```bash
sudo iptables -L INPUT --line-numbers          # note the number of the REJECT line, e.g. 5
sudo iptables -I INPUT 5 -p tcp -m state --state NEW --dport 80 -j ACCEPT
sudo iptables -I INPUT 5 -p tcp -m state --state NEW --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 5. Operating system

```bash
sudo timedatectl set-timezone UTC
sudo apt update && sudo apt full-upgrade -y
sudo apt install -y openjdk-21-jre-headless age curl unattended-upgrades
```

**SSH**: keys only, no root. `/etc/ssh/sshd_config.d/10-hardening.conf`:

```
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
```

then `sudo systemctl reload ssh` (test a second SSH session before closing the first).

**Automatic updates** with a reboot window after the nightly backup: `/etc/apt/apt.conf.d/52automatic-reboot`:

```
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:30";
```

A reboot logs everyone out of the portal (sessions are kept in memory); at 04:30 UTC that touches nobody.

**Journal retention** of 14 days, as the privacy notice says:

```bash
sudo mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nMaxRetentionSec=14day\n' | sudo tee /etc/systemd/journald.conf.d/retention.conf
sudo systemctl restart systemd-journald
```

(the same as [`journald-retention.conf`](journald-retention.conf)).

Optionally `sudo apt install fail2ban` (its default jail covers SSH).

## 6. Backup storage (Object Storage, instance principal)

The VM uploads backups with its own identity (*instance principal*), so no API key or password is stored on it. The policy lets it **add** backups but not read, change or delete them: someone who takes over the VM cannot destroy the backups. Old ones are deleted by a lifecycle rule.

1. *Identity → Domains → Default → Dynamic groups → Create*: name `mlaic-portal-vm`, rule
   `instance.id = '<the VM's OCID>'`
2. *Storage → Buckets → Create bucket*: name `mlaic-portal-backups`, Standard tier, **no** public access, encryption with Oracle-managed keys.
3. *Identity → Policies → Create policy* (in the compartment of the bucket):

   ```
   Allow dynamic-group mlaic-portal-vm to manage objects in compartment <compartment> where all {target.bucket.name='mlaic-portal-backups', any {request.permission='OBJECT_CREATE', request.permission='OBJECT_INSPECT'}}
   Allow service objectstorage-eu-frankfurt-1 to manage object-family in compartment <compartment>
   ```

   The second line lets the lifecycle rule delete objects (use your region's name).
4. Bucket → *Lifecycle policy rules → Create*: action **Delete**, after **30** days, all objects.
5. Install the OCI CLI on the VM and test the identity:

   ```bash
   bash -c "$(curl -fsSL https://raw.githubusercontent.com/oracle/oci-cli/master/scripts/install/install.sh)" -- \
     --accept-all-defaults --install-dir /opt/oci-cli --exec-dir /usr/local/bin
   oci os ns get --auth instance_principal          # prints the namespace: the VM may talk to Object Storage
   ```

## 7. Backup key (on your own computer)

The backups are encrypted with [age](https://age-encryption.org) to a public key; the private key never goes onto the VM.

```bash
age-keygen -o mlaic-portal-backup-key.txt      # prints "Public key: age1..."
```

Keep `mlaic-portal-backup-key.txt` offline, in two places (e.g. the password manager and a USB stick in the club's safe); the second organiser should be able to reach one of them. Without it no backup can be read. The public key (`age1...`) goes to the VM in the next step; a second organiser's own key can be added as a second line, then either can restore.

## 8. Install the portal

```bash
# user and folders
sudo useradd --system --home-dir /var/lib/mlaic-portal --shell /usr/sbin/nologin mlaic-portal
sudo install -d -m 0755 /etc/mlaic-portal
sudo install -d -m 0700 -o mlaic-portal -g mlaic-portal /var/backups/mlaic-portal

# the bundle, to get the files of this folder
cd /tmp && curl -fsSLO https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest/mlaic-portal-dist.tar.gz
mkdir bundle && tar -xzf mlaic-portal-dist.tar.gz -C bundle

# settings: put in the host name (PORTAL_BASE_URL) and the bucket
sudo cp bundle/deploy/portal.env.example /etc/mlaic-portal/portal.env
sudo nano /etc/mlaic-portal/portal.env
echo 'age1...your public key...' | sudo tee /etc/mlaic-portal/backup-recipients.txt

# services
sudo cp bundle/deploy/mlaic-portal.service bundle/deploy/mlaic-portal-backup.service bundle/deploy/mlaic-portal-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable mlaic-portal mlaic-portal-backup.timer

# install and start (the same script does every later update)
sudo bash bundle/deploy/portal-deploy.sh
rm -rf /tmp/bundle /tmp/mlaic-portal-dist.tar.gz

# the first organiser account (asks for the password twice)
sudo /opt/mlaic-portal/portal-cli.sh --add-organiser stefan
```

`curl -s http://127.0.0.1:8080/api/health` should answer `{"ok":true}`.

## 9. Caddy (HTTPS)

Install Caddy from its official repository (<https://caddyserver.com/docs/install#debian-ubuntu-raspbian>), then:

```bash
sudo cp /opt/mlaic-portal/deploy/Caddyfile.example /etc/caddy/Caddyfile
sudo nano /etc/caddy/Caddyfile          # host name and email address
sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
```

Check from your own computer:

```bash
curl -sI https://anmeldung.example.org/login/      # 200, strict-transport-security, content-security-policy
curl -m 5 http://<IP>:8080/api/health              # must time out: 8080 is not reachable
```

and run <https://www.ssllabs.com/ssltest/> on the host name: grade **A** expected.

Then log in at `https://<host>/login/`, turn on the authenticator app (*Account*), create the second organiser's account and enter the data controller (SV Bad Zell, address, email) for the privacy page.

## 10. Backups and the restore drill

```bash
sudo systemctl start mlaic-portal-backup        # one backup now
journalctl -u mlaic-portal-backup -n 20         # "Backup …" and "Uploaded to bucket …"
systemctl list-timers mlaic-portal-backup.timer # the next nightly run
```

**Restore drill, before go-live and once a season.** On your own computer, with the private key:

1. Download the newest object from the bucket (console: bucket → object → *Download*).
2. Unpack it and start a portal on it (Java 17+, the jar from the bundle):

   ```bash
   deploy/portal-restore.sh extract mlaic-portal-…-daily.tar.gz.age drill -i mlaic-portal-backup-key.txt
   PORTAL_DATA_DIR=drill PORTAL_PORT=8099 java -jar mlaic-portal.jar
   ```

3. Open `http://localhost:8099/login/`, log in, check meets, clubs and starters, download the export.
4. Delete `drill/` and the downloaded file (personal data).

**Restoring on the VM** (data lost or broken): decrypt on your computer (`age -d -i mlaic-portal-backup-key.txt -o b.tar.gz b.tar.gz.age`), copy `b.tar.gz` to the VM, then

```bash
sudo /opt/mlaic-portal/portal-restore.sh install b.tar.gz && rm b.tar.gz
```

It stops the portal, keeps the current data as `/var/lib/mlaic-portal.before-restore-<time>` (delete it once all is well), starts it again and waits for it to answer. Changes after the backup are lost: tell the clubs to check their lists.

## 11. Updates

```bash
sudo /opt/mlaic-portal/portal-deploy.sh
```

downloads the latest `portal-latest` build, checks its checksum, takes a backup, installs it and restarts the portal; if the new build does not answer, the previous one is put back. A restart logs everyone out: not in the last days before a deadline. When it reports that a unit file differs from the bundle's, compare them (`diff /opt/mlaic-portal/deploy/<unit> /etc/systemd/system/<unit>`) and copy the new one over, then `systemctl daemon-reload` and restart.

Operating-system updates install themselves (step 5). Once a month: `sudo apt full-upgrade`, look at `systemctl --failed`, and check whether the portal's dependencies have security fixes (GitHub's Dependabot alerts for the repository).

## 12. Monitoring

- **Uptime**: an external check of `https://<host>/api/health` every few minutes with an email alert (any free uptime service; it sees only that URL). `/api/health` answers 503 when the portal cannot read its data.
- **Failed backups**: `systemctl --failed` and `journalctl -u mlaic-portal-backup`; the bucket should get one new object a day.
- **Memory/CPU**: OCI *Monitoring → Alarm definitions* on the instance's `MemoryUtilization` > 90 % and `CpuUtilization` > 90 % (needs the Oracle Cloud Agent's monitoring plugin, on by default).
- **Disk**: `df -h /` now and then; the data are small, the journal and Caddy logs are capped.
- **Log-ins**: `journalctl -u mlaic-portal | grep -i "failed"` shows failed log-ins with the client address. Many from one address are blocked by the rate limits; many from many addresses: consider closing the registration early and replacing the club codes.

## 13. A season

1. In the Manager: export the meet's rankings and the discipline catalog.
2. Portal *Meets*: create the meet (deadline, deletion date = meet date + 90 days), upload both files, check the offered rankings.
3. *Clubs*: create each club, send its invite link **to the club's contact person only** (email or in person, not in a public group).
4. Open the registration. Clubs enter and change their starters until the deadline; then the sheets are read-only, late entries go through *Starters → Edit list*.
5. *Download*: the file for the Manager's **Import Registrations** page. Import again after late entries; nothing is duplicated.
6. The portal deletes the meet's registrations at the deletion date by itself; backups follow 30 days later.

## 14. Data protection records

**Records of processing** (Art. 30 GDPR), entry to keep with SV Bad Zell's other records:

| | |
|---|---|
| Controller | SV Bad Zell (address, contact as on the privacy page) |
| Purpose | Registration of starters for MLAIC meets organised by the controller; transfer to the competition software |
| Legal basis | Art. 6(1)(b) GDPR (taking part in the competition at the starter's request, via the club); for the club contacts Art. 6(1)(f) |
| Data subjects | Starters; contact persons of the clubs; organisers |
| Data | Starters: name, gender, year of birth, club, country, rankings entered, optionally email. Contacts: name, email. Organisers: user name, password hash. Technical: IP address in logs (14 days) |
| Recipients | The organiser (SV Bad Zell); each club sees its own entries only; Oracle (hosting, processor, EU region) |
| Third countries | None by design (EU region); Oracle's data processing agreement covers its sub-processors |
| Deletion | Meet date + 90 days automatically; backups 30 days after that; logs 14 days |
| Measures | HTTPS only (HSTS), strict CSP; Argon2id passwords, authenticator app, rate limits; club codes 80 bits, hashed, revocable; each club limited to its own rows; data folder readable by the service only, service sandboxed without network access beyond the proxy; encrypted backups (key offline); EU region; access logs; automatic updates |

The data processing agreement with Oracle is part of the Oracle Cloud Services Agreement (Oracle Data Processing Agreement); keep a copy with the records.

Still to settle (plan, *Still open*): whether the clubs pass their members' data on as separate controllers (the usual case) or act on SV Bad Zell's behalf; it changes only the privacy notice's wording and the clubs' confirmation.

## 15. Data breach procedure

A breach is any loss of confidentiality, integrity or availability of personal data: e.g. someone gets a club's code or an organiser's password, the VM is compromised, a backup key is lost, data are deleted by mistake.

1. **Contain** at once: close the registration (*Meets*), replace or block affected club codes (*Clubs*), change organiser passwords and reset the authenticator app (`portal-cli.sh`); if the VM is suspect, stop it in the OCI console (data stay on the boot volume).
2. **Assess**: what data, how many people, since when (`journalctl -u mlaic-portal`, the meet's change log, Caddy's access log).
3. **Report** to the Austrian data protection authority (Datenschutzbehörde, <https://www.dsb.gv.at>) **within 72 hours** of becoming aware, unless the breach is unlikely to result in a risk to the people concerned.
4. **Inform the people** (via their clubs) without delay if the risk to them is high.
5. **Record** every breach, reported or not: what happened, effects, measures taken.
6. **Recover**: if needed, rebuild the VM from this runbook and restore the last clean backup (step 10).

## 16. Before go-live

- [ ] DNS name points to the reserved IP; `https://<host>/login/` loads with a valid certificate; SSL Labs grade A
- [ ] Port 8080 not reachable from outside; SSH only from your address, keys only
- [ ] Two organiser accounts, both with the authenticator app
- [ ] Data controller entered; the privacy page shows it
- [ ] A backup in the bucket, and the restore drill done with it
- [ ] Uptime check on `/api/health` alerting your email
- [ ] Budget alert at €1
- [ ] The backup private key stored in two places
