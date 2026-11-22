<#
.SYNOPSIS
  Isolated native Windows integration & unit test suite for deploy/backup/pc/pc-pull.ps1 (ADR-0017, #17).
.DESCRIPTION
  Runs real encrypted local restic repositories (zero OCI/S3/network/provider access, zero global installs).
  Tests:
    1. Guard rails: remote destination rejected (DEST_MUST_BE_LOCAL), Git checkout / OneDrive rejected,
       BACKUP_KEEP_DAILY rejected, unrestricted Windows ACL rejected (ENV_ACL_FAIL) vs restricted ACL accepted.
    2. Init subcommand (`init --from-repo --copy-chunker-params` against NTFS read-only source, idempotent re-init).
    3. 14-day wall-clock boundary (14d-1s & exact 14d kept, 14d+1s deleted), foreign tag preservation (30d kept),
       read-only source copy (--no-lock), original snapshot.time preserved, old (>14d) source snapshot NOT copied,
       idempotent re-copy, and real local HTTP heartbeat success POST.
    4. All expired -> 0 remaining snapshots allowed (both source and local empty/0 kept -> PC_PULL_OK).
    5. Source unreachable / bad source password -> local PRE-expiry STILL runs & physically prunes >14d before failing
       with /fail heartbeat (and local wrong password fails explicitly with LOCAL_REPO_AUTH_FAIL).
    6. Destination lock preserved even when --no-lock is passed (read-only dst/locks fails copy).
    7. Copy partial failure / source prune race (missing source data pack) -> partial snapshot NOT saved,
       post-copy local prune --max-unused 0 runs, failure NOT hidden (/fail ping, exit != 0).
    8. Snapshot crossing 14-day boundary DURING copy -> post-copy retention immediately forgets & prunes it.
    9. Single-instance OS lock exclusion (LOCK_HELD, exit 20).
   10. Secret redaction across stdout, stderr, log file, and /fail heartbeat body (including regex/special chars).
#>
[CmdletBinding()]
param(
  [string]$ResticBin = "",
  [string]$PythonBin = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$REPO_ROOT = Split-Path -Parent (Split-Path -Parent $SCRIPT_DIR)
$PC_PULL = Join-Path $REPO_ROOT "deploy\backup\pc\pc-pull.ps1"
$EXPIRE_SELECT = Join-Path $REPO_ROOT "deploy\backup\expire-select.py"

if ([string]::IsNullOrWhiteSpace($ResticBin)) {
  if (-not [string]::IsNullOrWhiteSpace($env:RESTIC_BIN)) {
    $ResticBin = $env:RESTIC_BIN
  } else {
    $ResticBin = "restic"
  }
}
if ([string]::IsNullOrWhiteSpace($PythonBin)) {
  if (-not [string]::IsNullOrWhiteSpace($env:PYTHON_BIN)) {
    $PythonBin = $env:PYTHON_BIN
  } else {
    $PythonBin = "python"
  }
}

$DAY = 86400
$WINDOW = 14 * $DAY
$BASE_NOW = 1800000000L

$TMP_ROOT = Join-Path ([System.IO.Path]::GetTempPath()) ("gongchae-pc-pull-test-" + [Guid]::NewGuid().ToString("N").Substring(0, 10))
New-Item -ItemType Directory -Path $TMP_ROOT -Force | Out-Null

$script:ReadOnlyDirsToRestore = @()

function Format-UtcIso {
  param([long]$Epoch)
  return [DateTimeOffset]::FromUnixTimeSeconds($Epoch).ToString("yyyy-MM-ddTHH:mm:ssZ")
}

function Format-ResticTime {
  param([long]$Epoch)
  # restic backup --time parses "YYYY-MM-DD HH:MM:SS" in the OS local timezone (ignoring TZ on Windows),
  # so converting the exact UTC epoch to local time produces the exact same UTC instant on KST and UTC runners.
  return [DateTimeOffset]::FromUnixTimeSeconds($Epoch).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss")
}

function Assert-True {
  param([bool]$Cond, [string]$Msg)
  if (-not $Cond) { throw "ASSERT_TRUE FAILED: $Msg" }
}

function Assert-Eq {
  param($Expected, $Actual, [string]$Msg)
  if ("$Expected" -ne "$Actual") {
    throw "ASSERT_EQ FAILED ($Msg): expected=[$Expected] actual=[$Actual]"
  }
}

function Assert-Contains {
  param([string]$Text, [string]$Needle, [string]$Msg)
  if ([string]::IsNullOrEmpty($Text) -or $Text.IndexOf($Needle, [System.StringComparison]::Ordinal) -lt 0) {
    throw "ASSERT_CONTAINS FAILED ($Msg): missing [$Needle] in:`n$Text"
  }
}

function Assert-NotContains {
  param([string]$Text, [string]$Needle, [string]$Msg)
  if (-not [string]::IsNullOrEmpty($Text) -and $Text.IndexOf($Needle, [System.StringComparison]::Ordinal) -ge 0) {
    throw "ASSERT_NOT_CONTAINS FAILED ($Msg): unexpectedly contains [$Needle] in:`n$Text"
  }
}

function Set-RestrictedAcl {
  param([string]$Path)
  if ($env:OS -ne "Windows_NT") { return }
  $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
  $grant = if (Test-Path -LiteralPath $Path -PathType Container) {
    @("/grant:r", "${me}:(OI)(CI)F", "/grant:r", "SYSTEM:(OI)(CI)F", "/grant:r", "Administrators:(OI)(CI)F")
  } else {
    @("/grant:r", "${me}:F", "/grant:r", "SYSTEM:F", "/grant:r", "Administrators:F")
  }
  $args = @($Path, "/inheritance:r") + $grant
  $out = & icacls.exe @args 2>&1
  if ($LASTEXITCODE -ne 0) { throw "icacls restrict failed on $Path : $out" }
}

function Set-ReadOnlyTreeAcl {
  param([string]$DirPath)
  if ($env:OS -ne "Windows_NT") {
    & chmod -R a-w $DirPath
    return
  }
  $script:ReadOnlyDirsToRestore += $DirPath
  $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
  $acl = [System.IO.Directory]::GetAccessControl($DirPath)
  $acl.SetAccessRuleProtection($true, $false)
  $inherit = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
  $prop = [System.Security.AccessControl.PropagationFlags]::None
  $allowMe = New-Object System.Security.AccessControl.FileSystemAccessRule(
    $me,
    ([System.Security.AccessControl.FileSystemRights]::ReadAndExecute -bor [System.Security.AccessControl.FileSystemRights]::ChangePermissions),
    $inherit, $prop, "Allow"
  )
  $allowSys = New-Object System.Security.AccessControl.FileSystemAccessRule("NT AUTHORITY\SYSTEM", "FullControl", $inherit, $prop, "Allow")
  $allowAdm = New-Object System.Security.AccessControl.FileSystemAccessRule("BUILTIN\Administrators", "FullControl", $inherit, $prop, "Allow")
  $denyWrite = New-Object System.Security.AccessControl.FileSystemAccessRule(
    $me,
    ([System.Security.AccessControl.FileSystemRights]::WriteData -bor [System.Security.AccessControl.FileSystemRights]::AppendData -bor [System.Security.AccessControl.FileSystemRights]::Delete -bor [System.Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles),
    $inherit, $prop, "Deny"
  )
  $acl.AddAccessRule($allowMe)
  $acl.AddAccessRule($allowSys)
  $acl.AddAccessRule($allowAdm)
  $acl.AddAccessRule($denyWrite)
  [System.IO.Directory]::SetAccessControl($DirPath, $acl)
}

function Restore-WritableTreeAcl {
  param([string]$DirPath)
  if (-not (Test-Path -LiteralPath $DirPath)) { return }
  if ($env:OS -ne "Windows_NT") {
    & chmod -R u+w $DirPath
    return
  }
  $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
  $acl = [System.IO.Directory]::GetAccessControl($DirPath)
  $inherit = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
  $prop = [System.Security.AccessControl.PropagationFlags]::None
  $denyWrite = New-Object System.Security.AccessControl.FileSystemAccessRule(
    $me,
    ([System.Security.AccessControl.FileSystemRights]::WriteData -bor [System.Security.AccessControl.FileSystemRights]::AppendData -bor [System.Security.AccessControl.FileSystemRights]::Delete -bor [System.Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles),
    $inherit, $prop, "Deny"
  )
  $null = $acl.RemoveAccessRule($denyWrite)
  $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($me, "FullControl", $inherit, $prop, "Allow")))
  [System.IO.Directory]::SetAccessControl($DirPath, $acl)
}

function Invoke-ResticDirect {
  param(
    [string]$Repo,
    [string]$Password,
    [string]$CacheDir,
    [string[]]$ArgsList
  )
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $ResticBin
  $escaped = $ArgsList | ForEach-Object {
    if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
  }
  $psi.Arguments = ($escaped -join " ")
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
  $psi.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)
  $psi.EnvironmentVariables["RESTIC_REPOSITORY"] = $Repo
  $psi.EnvironmentVariables["RESTIC_PASSWORD"] = $Password
  $psi.EnvironmentVariables["RESTIC_CACHE_DIR"] = $CacheDir
  $psi.EnvironmentVariables["TZ"] = "UTC"
  $proc = [System.Diagnostics.Process]::Start($psi)
  $outTask = $proc.StandardOutput.ReadToEndAsync()
  $errTask = $proc.StandardError.ReadToEndAsync()
  $proc.WaitForExit()
  $res = [pscustomobject]@{
    ExitCode = $proc.ExitCode
    StdOut   = $outTask.GetAwaiter().GetResult()
    StdErr   = $errTask.GetAwaiter().GetResult()
  }
  $proc.Dispose()
  return $res
}

function Invoke-PcPull {
  param(
    [string]$Subcommand,
    [string]$EnvPath,
    [switch]$StandaloneInit
  )
  $psExe = (Get-Process -Id $PID).Path
  $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $PC_PULL, "-Command", $Subcommand, "-EnvFile", $EnvPath)
  if ($StandaloneInit) { $argList += "-StandaloneInit" }

  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $psExe
  $escaped = $argList | ForEach-Object {
    if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
  }
  $psi.Arguments = ($escaped -join " ")
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
  $psi.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)
  $proc = [System.Diagnostics.Process]::Start($psi)
  $outTask = $proc.StandardOutput.ReadToEndAsync()
  $errTask = $proc.StandardError.ReadToEndAsync()
  $proc.WaitForExit()
  $res = [pscustomobject]@{
    ExitCode = $proc.ExitCode
    StdOut   = $outTask.GetAwaiter().GetResult()
    StdErr   = $errTask.GetAwaiter().GetResult()
  }
  $proc.Dispose()
  return $res
}

function Get-RepoSnapshots {
  param([string]$Repo, [string]$Password, [string]$CacheDir, [string]$Tag = "")
  $args = @("--no-lock", "snapshots", "--json")
  if (-not [string]::IsNullOrEmpty($Tag)) { $args += @("--tag", $Tag) }
  $r = Invoke-ResticDirect -Repo $Repo -Password $Password -CacheDir $CacheDir -ArgsList $args
  if ($r.ExitCode -ne 0) { throw "snapshots failed: $($r.StdErr)" }
  $parsed = $r.StdOut | ConvertFrom-Json
  if ($null -eq $parsed) { return @() }
  return @($parsed)
}

function Write-CaseEnv {
  param(
    [string]$Path,
    [hashtable]$Values,
    [bool]$RestrictAcl = $true
  )
  $lines = @()
  foreach ($k in $Values.Keys) {
    $lines += "$k=$($Values[$k])"
  }
  [System.IO.File]::WriteAllLines($Path, $lines, [System.Text.UTF8Encoding]::new($false))
  if ($RestrictAcl) {
    Set-RestrictedAcl $Path
  }
}

function Start-MockHeartbeatServer {
  param([string]$LogFile)
  # Pick an ephemeral localhost TCP port
  $tcp = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
  $tcp.Start()
  $port = $tcp.LocalEndpoint.Port
  $tcp.Stop()

  $pyServer = @"
import http.server, sys
log_path = sys.argv[1]
port = int(sys.argv[2])
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', '0'))
        body = self.rfile.read(length).decode('utf-8', errors='replace')
        with open(log_path, 'a', encoding='utf-8') as f:
            f.write(f'{self.path}|{body}\n')
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'OK')
    def log_message(self, *args):
        pass
srv = http.server.HTTPServer(('127.0.0.1', port), H)
srv.serve_forever()
"@
  $pyPath = Join-Path $TMP_ROOT "mock_hc_server.py"
  [System.IO.File]::WriteAllText($pyPath, $pyServer, [System.Text.UTF8Encoding]::new($false))
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $PythonBin
  $psi.Arguments = "-I `"$pyPath`" `"$LogFile`" $port"
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $proc = [System.Diagnostics.Process]::Start($psi)
  Start-Sleep -Milliseconds 400
  return [pscustomobject]@{
    Process = $proc
    Port    = $port
    Url     = "http://127.0.0.1:$port/deadbeef-pc-token-9999"
    LogFile = $LogFile
  }
}

$hbServer = $null
try {
  # Verify restic & python availability
  $ver = Invoke-ResticDirect -Repo "" -Password "" -CacheDir $TMP_ROOT -ArgsList @("version")
  Assert-Eq 0 $ver.ExitCode "restic binary must be runnable"
  Write-Host ("Using restic: " + $ver.StdOut.Trim())

  $hbLog = Join-Path $TMP_ROOT "hb.log"
  $hbServer = Start-MockHeartbeatServer -LogFile $hbLog

  # ---------------------------------------------------------------------------
  Write-Host "=== [1/10] Guard rails: remote dest, Git checkout, BACKUP_KEEP_DAILY, Windows ACL ==="
  $c1 = Join-Path $TMP_ROOT "case1"
  New-Item -ItemType Directory -Path $c1 -Force | Out-Null
  $pwFile1 = Join-Path $c1 "pc.pass"
  [System.IO.File]::WriteAllText($pwFile1, "local-pass-1`n", [System.Text.UTF8Encoding]::new($false))
  Set-RestrictedAcl $pwFile1

  # 1a: remote s3: destination must be rejected
  $env1a = Join-Path $c1 "env1a.env"
  Write-CaseEnv $env1a @{
    PC_RESTIC_REPOSITORY     = "s3:https://ns.compat.objectstorage.ap-tokyo-1.oraclecloud.com/wrong-bucket"
    PC_RESTIC_PASSWORD_FILE  = $pwFile1
    PC_ALLOW_CUSTOM_PATHS    = "true"
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
  }
  $r1a = Invoke-PcPull -Subcommand "run" -EnvPath $env1a
  Assert-True ($r1a.ExitCode -ne 0) "s3 destination must fail"
  Assert-Contains ($r1a.StdOut + $r1a.StdErr) "DEST_MUST_BE_LOCAL" "s3 dest reason"

  # 1a-unc: UNC network share destination must be rejected
  $env1aUnc = Join-Path $c1 "env1a-unc.env"
  Write-CaseEnv $env1aUnc @{
    PC_RESTIC_REPOSITORY     = "\\fileserver.local\share\backup"
    PC_RESTIC_PASSWORD_FILE  = $pwFile1
    PC_ALLOW_CUSTOM_PATHS    = "true"
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
  }
  $r1aUnc = Invoke-PcPull -Subcommand "run" -EnvPath $env1aUnc
  Assert-True ($r1aUnc.ExitCode -ne 0) "UNC destination must fail"
  Assert-Contains ($r1aUnc.StdOut + $r1aUnc.StdErr) "DEST_MUST_BE_LOCAL" "UNC dest reason"

  # 1b: Git checkout destination must be rejected when PC_ALLOW_CUSTOM_PATHS is not set
  $env1b = Join-Path $c1 "env1b.env"
  Write-CaseEnv $env1b @{
    PC_RESTIC_REPOSITORY     = (Join-Path $REPO_ROOT "tmp-forbidden-backup")
    PC_RESTIC_PASSWORD_FILE  = $pwFile1
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
  }
  $r1b = Invoke-PcPull -Subcommand "run" -EnvPath $env1b
  Assert-True ($r1b.ExitCode -ne 0) "git checkout dest must fail"
  Assert-Contains ($r1b.StdOut + $r1b.StdErr) "DEST_GIT_REPO_FORBIDDEN" "git dest reason"

  # 1c: BACKUP_KEEP_DAILY rejected
  $env1c = Join-Path $c1 "env1c.env"
  Write-CaseEnv $env1c @{
    PC_RESTIC_REPOSITORY     = (Join-Path $c1 "dst")
    PC_RESTIC_PASSWORD_FILE  = $pwFile1
    BACKUP_KEEP_DAILY        = "14"
    PC_ALLOW_CUSTOM_PATHS    = "true"
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
  }
  $r1c = Invoke-PcPull -Subcommand "run" -EnvPath $env1c
  Assert-True ($r1c.ExitCode -ne 0) "BACKUP_KEEP_DAILY must fail"
  Assert-Contains ($r1c.StdOut + $r1c.StdErr) "BACKUP_KEEP_DAILY is no longer supported" "removed knob rejected"

  # 1d: Unrestricted ACL (granting BUILTIN\Users) rejected on Windows
  if ($env:OS -eq "Windows_NT") {
    $env1d = Join-Path $c1 "env1d.env"
    Write-CaseEnv $env1d @{
      PC_RESTIC_REPOSITORY    = (Join-Path $c1 "dst")
      PC_RESTIC_PASSWORD_FILE = $pwFile1
      PC_ALLOW_CUSTOM_PATHS   = "true"
      RESTIC_BIN              = $ResticBin
      PYTHON_BIN              = $PythonBin
      EXPIRE_SELECT_PATH      = $EXPIRE_SELECT
    } -RestrictAcl $false
    $null = & icacls.exe $env1d /grant "Users:R" 2>&1
    $r1d = Invoke-PcPull -Subcommand "run" -EnvPath $env1d
    Assert-True ($r1d.ExitCode -ne 0) "broad ACL env file must fail"
    Assert-Contains ($r1d.StdOut + $r1d.StdErr) "ENV_ACL_FAIL" "broad ACL error code"
  }

  # ---------------------------------------------------------------------------
  Write-Host "=== [2/10] Init (--copy-chunker-params from RO source) + [3/10] 14d boundary, foreign tag, RO copy, idempotency ==="
  $c2 = Join-Path $TMP_ROOT "case2"
  $src2 = Join-Path $c2 "src-oci"
  $dst2 = Join-Path $c2 "dst-pc"
  $cache2 = Join-Path $c2 "cache"
  $data2 = Join-Path $c2 "payload"
  New-Item -ItemType Directory -Path $src2, $cache2, $data2 -Force | Out-Null

  $srcPass = 'srcP@ss/w&rd$1!'
  $dstPass = 'dstP@ss/w&rd$2!'
  $srcPwFile = Join-Path $c2 "oci.pass"
  $dstPwFile = Join-Path $c2 "pc.pass"
  [System.IO.File]::WriteAllText($srcPwFile, $srcPass, [System.Text.UTF8Encoding]::new($false))
  [System.IO.File]::WriteAllText($dstPwFile, $dstPass, [System.Text.UTF8Encoding]::new($false))
  Set-RestrictedAcl $srcPwFile
  Set-RestrictedAcl $dstPwFile

  # Create real encrypted source repo with 4 snapshots:
  # - 20d old gongchae-bell (MUST NOT be copied to PC!)
  # - 14d+1s old gongchae-bell (MUST NOT be copied to PC!)
  # - exact 14d old gongchae-bell (MUST be copied & kept!)
  # - 14d-1s old gongchae-bell (MUST be copied & kept!)
  Assert-Eq 0 (Invoke-ResticDirect -Repo $src2 -Password $srcPass -CacheDir $cache2 -ArgsList @("init")).ExitCode "src init"
  foreach ($spec in @(
    @{ Name = "old20d.txt"; Offset = -(20 * $DAY) },
    @{ Name = "old14d1s.txt"; Offset = -($WINDOW + 1) },
    @{ Name = "exact14d.txt"; Offset = -$WINDOW },
    @{ Name = "fresh14d1s.txt"; Offset = -($WINDOW - 1) }
  )) {
    [System.IO.File]::WriteAllText((Join-Path $data2 "snap.txt"), "payload-$($spec.Name)")
    $tStr = Format-ResticTime ($BASE_NOW + $spec.Offset)
    $br = Invoke-ResticDirect -Repo $src2 -Password $srcPass -CacheDir $cache2 -ArgsList @("backup", "--tag", "gongchae-bell", "--time", $tStr, $data2)
    Assert-Eq 0 $br.ExitCode "src backup $($spec.Name)"
  }
  # Also add a foreign-tag 30d snapshot in src (must never be selected)
  [System.IO.File]::WriteAllText((Join-Path $data2 "snap.txt"), "foreign-src-only")
  $brForeignSrc = Invoke-ResticDirect -Repo $src2 -Password $srcPass -CacheDir $cache2 -ArgsList @("backup", "--tag", "other-app", "--time", (Format-ResticTime ($BASE_NOW - 30 * $DAY)), $data2)
  Assert-Eq 0 $brForeignSrc.ExitCode "src foreign backup"

  # Lock source strictly READ-ONLY (NTFS ACL) to prove --no-lock does zero writes to source!
  Set-ReadOnlyTreeAcl $src2
  # Sanity proof: without --no-lock, restic snapshots on RO source MUST fail to create a lock file.
  $roProof = Invoke-ResticDirect -Repo $src2 -Password $srcPass -CacheDir $cache2 -ArgsList @("snapshots", "--json")
  Assert-True ($roProof.ExitCode -ne 0) "RO source ACL must reject lock creation without --no-lock"

  $env2 = Join-Path $c2 "pc-pull.env"
  Write-CaseEnv $env2 @{
    PC_RESTIC_REPOSITORY     = $dst2
    PC_RESTIC_PASSWORD_FILE  = $dstPwFile
    OCI_RESTIC_REPOSITORY    = $src2
    OCI_RESTIC_PASSWORD_FILE = $srcPwFile
    HEALTHCHECKS_PC_PULL_URL = $hbServer.Url
    PC_LOCK_FILE             = (Join-Path $c2 "pc-pull.lock")
    PC_CACHE_DIR             = $cache2
    PC_LOG_DIR               = (Join-Path $c2 "logs")
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS    = "true"
    PC_ALLOW_LOCAL_SOURCE    = "true"
    PC_NOW_EPOCH_OVERRIDE    = "$BASE_NOW"
    COPY_MAX_RETRIES         = "0"
  }

  # Init local PC repo from strictly read-only source (--copy-chunker-params --no-lock)
  $rInit = Invoke-PcPull -Subcommand "init" -EnvPath $env2
  Assert-Eq 0 $rInit.ExitCode "init from RO src must succeed: $($rInit.StdOut) $($rInit.StdErr)"
  Assert-Contains $rInit.StdOut "PC_RESTIC_REPO_INITIALIZED mode=copy-chunker-params" "init logged"

  # Idempotent re-init
  $rInit2 = Invoke-PcPull -Subcommand "init" -EnvPath $env2
  Assert-Eq 0 $rInit2.ExitCode "re-init must succeed"
  Assert-Contains $rInit2.StdOut "PC_RESTIC_REPO_ALREADY_INITIALIZED" "already initialized logged"

  # Seed local PC repo with:
  # - 18d old gongchae-bell snapshot (MUST be forgotten & physically pruned during PRE-expiry!)
  # - 30d old foreign-tag snapshot (MUST remain intact & restorable!)
  [System.IO.File]::WriteAllText((Join-Path $data2 "snap.txt"), "local-expired-unique-secret-blob")
  Assert-Eq 0 (Invoke-ResticDirect -Repo $dst2 -Password $dstPass -CacheDir $cache2 -ArgsList @("backup", "--tag", "gongchae-bell", "--time", (Format-ResticTime ($BASE_NOW - 18 * $DAY)), $data2)).ExitCode "dst expired seed"
  [System.IO.File]::WriteAllText((Join-Path $data2 "snap.txt"), "foreign-local-preserved-30d")
  Assert-Eq 0 (Invoke-ResticDirect -Repo $dst2 -Password $dstPass -CacheDir $cache2 -ArgsList @("backup", "--tag", "foreign-tag", "--time", (Format-ResticTime ($BASE_NOW - 30 * $DAY)), $data2)).ExitCode "dst foreign seed"

  # Run PC pull!
  if (Test-Path $hbLog) { Remove-Item $hbLog -Force }
  $rRun = Invoke-PcPull -Subcommand "run" -EnvPath $env2
  Assert-Eq 0 $rRun.ExitCode "happy run must succeed: $($rRun.StdOut) $($rRun.StdErr)"
  Assert-Contains $rRun.StdOut "PRE_RETENTION_SUMMARY policy=wall-clock-14d forgotten=1 total=0 expire=0 keep=0" "pre-expiry deleted 18d local copy"
  Assert-Contains $rRun.StdOut "SOURCE_COPY_OK candidates=2" "only 2 non-expired source snapshots copied (14d exact and 14d-1s)"
  Assert-Contains $rRun.StdOut "POST_RETENTION_SUMMARY policy=wall-clock-14d forgotten=0 total=2 expire=0 keep=2" "post-retention verified 2 kept"
  Assert-Contains $rRun.StdOut "PC_PULL_OK" "run ok logged"

  # Verify exact snapshot times preserved and old source snapshots NOT copied
  $dstSnaps = @(Get-RepoSnapshots -Repo $dst2 -Password $dstPass -CacheDir $cache2 -Tag "gongchae-bell")
  Assert-Eq 2 $dstSnaps.Count "exactly 2 gongchae-bell snapshots in PC repo"
  $dstTimes = @($dstSnaps | ForEach-Object { ([DateTimeOffset]::Parse($_.time)).ToUnixTimeSeconds() } | Sort-Object)
  Assert-Eq ($BASE_NOW - $WINDOW) $dstTimes[0] "exact 14d original timestamp preserved"
  Assert-Eq ($BASE_NOW - $WINDOW + 1) $dstTimes[1] "14d-1s original timestamp preserved"

  # Verify foreign-tag 30d snapshot in PC repo is untouched
  $foreignSnaps = @(Get-RepoSnapshots -Repo $dst2 -Password $dstPass -CacheDir $cache2 -Tag "foreign-tag")
  Assert-Eq 1 $foreignSnaps.Count "foreign-tag 30d snapshot preserved"

  # Verify source repo still has 0 locks
  $srcLocks = @(Get-ChildItem -Path (Join-Path $src2 "locks") -Force -ErrorAction SilentlyContinue)
  Assert-Eq 0 $srcLocks.Count "zero lock files written to read-only source"

  # Verify success heartbeat sent (no /fail)
  $hbContent = [System.IO.File]::ReadAllText($hbLog)
  Assert-Contains $hbContent "/deadbeef-pc-token-9999|PC_PULL_OK" "success heartbeat delivered"
  Assert-NotContains $hbContent "/fail" "no fail ping on success"

  # Verify idempotent re-run does not duplicate snapshots
  $rRun2 = Invoke-PcPull -Subcommand "run" -EnvPath $env2
  Assert-Eq 0 $rRun2.ExitCode "idempotent re-run must succeed"
  $dstSnaps2 = @(Get-RepoSnapshots -Repo $dst2 -Password $dstPass -CacheDir $cache2 -Tag "gongchae-bell")
  Assert-Eq 2 $dstSnaps2.Count "idempotent re-run keeps exactly 2 snapshots (no duplicates)"

  # ---------------------------------------------------------------------------
  Write-Host "=== [4/10] All expired -> 0 remaining copies allowed (both local and source) ==="
  $allExpiredNow = $BASE_NOW + 10 * $DAY
  $env4 = Join-Path $c2 "pc-pull-zero.env"
  Write-CaseEnv $env4 @{
    PC_RESTIC_REPOSITORY     = $dst2
    PC_RESTIC_PASSWORD_FILE  = $dstPwFile
    OCI_RESTIC_REPOSITORY    = $src2
    OCI_RESTIC_PASSWORD_FILE = $srcPwFile
    HEALTHCHECKS_PC_PULL_URL = $hbServer.Url
    PC_LOCK_FILE             = (Join-Path $c2 "pc-pull.lock")
    PC_CACHE_DIR             = $cache2
    PC_LOG_DIR               = (Join-Path $c2 "logs")
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS    = "true"
    PC_ALLOW_LOCAL_SOURCE    = "true"
    PC_NOW_EPOCH_OVERRIDE    = "$allExpiredNow"
    COPY_MAX_RETRIES         = "0"
  }
  $rZero = Invoke-PcPull -Subcommand "run" -EnvPath $env4
  Assert-Eq 0 $rZero.ExitCode "all-expired 0-copy run must succeed: $($rZero.StdOut) $($rZero.StdErr)"
  Assert-Contains $rZero.StdOut "PRE_RETENTION_SUMMARY policy=wall-clock-14d forgotten=2 total=0 expire=0 keep=0" "all local copies deleted"
  Assert-Contains $rZero.StdOut "SOURCE_COPY_SKIPPED valid_candidates=0" "no expired source snapshots copied"
  Assert-Contains $rZero.StdOut "PC_PULL_OK" "0 copies remaining is valid success"
  Assert-Eq 0 @(Get-RepoSnapshots -Repo $dst2 -Password $dstPass -CacheDir $cache2 -Tag "gongchae-bell").Count "0 gongchae-bell snapshots left"
  Assert-Eq 1 @(Get-RepoSnapshots -Repo $dst2 -Password $dstPass -CacheDir $cache2 -Tag "foreign-tag").Count "foreign-tag still untouched"

  # ---------------------------------------------------------------------------
  Write-Host "=== [5/10] Source failure still runs local PRE-expiry & prunes; local bad password fails explicitly ==="
  $c5 = Join-Path $TMP_ROOT "case5"
  $dst5 = Join-Path $c5 "dst-pc"
  $cache5 = Join-Path $c5 "cache"
  $data5 = Join-Path $c5 "data"
  New-Item -ItemType Directory -Path $dst5, $cache5, $data5 -Force | Out-Null
  Assert-Eq 0 (Invoke-ResticDirect -Repo $dst5 -Password $dstPass -CacheDir $cache5 -ArgsList @("init")).ExitCode "c5 init"
  [System.IO.File]::WriteAllText((Join-Path $data5 "a.txt"), "expired-despite-source-failure")
  Assert-Eq 0 (Invoke-ResticDirect -Repo $dst5 -Password $dstPass -CacheDir $cache5 -ArgsList @("backup", "--tag", "gongchae-bell", "--time", (Format-ResticTime ($BASE_NOW - 20 * $DAY)), $data5)).ExitCode "c5 old snap"

  $env5 = Join-Path $c5 "src-fail.env"
  Write-CaseEnv $env5 @{
    PC_RESTIC_REPOSITORY     = $dst5
    PC_RESTIC_PASSWORD_FILE  = $dstPwFile
    OCI_RESTIC_REPOSITORY    = (Join-Path $c5 "missing-source-repo")
    OCI_RESTIC_PASSWORD      = "wrong-source-password-SECRET-XYZ"
    HEALTHCHECKS_PC_PULL_URL = $hbServer.Url
    PC_LOCK_FILE             = (Join-Path $c5 "pc-pull.lock")
    PC_CACHE_DIR             = $cache5
    PC_LOG_DIR               = (Join-Path $c5 "logs")
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS    = "true"
    PC_ALLOW_LOCAL_SOURCE    = "true"
    PC_NOW_EPOCH_OVERRIDE    = "$BASE_NOW"
    COPY_MAX_RETRIES         = "0"
    COPY_RETRY_DELAY_SECONDS = "0"
  }
  if (Test-Path $hbLog) { Remove-Item $hbLog -Force }
  $r5 = Invoke-PcPull -Subcommand "run" -EnvPath $env5
  Assert-True ($r5.ExitCode -ne 0) "missing source must exit non-zero"
  Assert-Contains $r5.StdOut "PRE_RETENTION_SUMMARY policy=wall-clock-14d forgotten=1 total=0 expire=0 keep=0" "local pre-expiry ran BEFORE source check"
  Assert-Contains ($r5.StdOut + $r5.StdErr) "LOCAL_RETENTION_OK_BUT_SOURCE_FAILED" "distinguished local retention ok vs source fail"
  Assert-Eq 0 @(Get-RepoSnapshots -Repo $dst5 -Password $dstPass -CacheDir $cache5 -Tag "gongchae-bell").Count "local expired snapshot actually deleted"
  Assert-Contains ([System.IO.File]::ReadAllText($hbLog)) "/deadbeef-pc-token-9999/fail|" "/fail ping delivered on source failure"

  # 5a-retry: missing source with COPY_MAX_RETRIES=1 and delay=0 must retry (attempt 1/2 -> attempt 2/2)
  $env5Retry = Join-Path $c5 "src-retry.env"
  Write-CaseEnv $env5Retry @{
    PC_RESTIC_REPOSITORY     = $dst5
    PC_RESTIC_PASSWORD_FILE  = $dstPwFile
    OCI_RESTIC_REPOSITORY    = (Join-Path $c5 "missing-source-repo")
    OCI_RESTIC_PASSWORD      = "wrong-source-password-SECRET-XYZ"
    PC_LOCK_FILE             = (Join-Path $c5 "pc-pull.lock")
    PC_CACHE_DIR             = $cache5
    PC_LOG_DIR               = (Join-Path $c5 "logs")
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS    = "true"
    PC_ALLOW_LOCAL_SOURCE    = "true"
    PC_NOW_EPOCH_OVERRIDE    = "$BASE_NOW"
    COPY_MAX_RETRIES         = "1"
    COPY_RETRY_DELAY_SECONDS = "0"
  }
  $r5Retry = Invoke-PcPull -Subcommand "run" -EnvPath $env5Retry
  Assert-True ($r5Retry.ExitCode -ne 0) "retry missing source must fail"
  Assert-Contains ($r5Retry.StdOut + $r5Retry.StdErr) "attempt 1/2" "first source snapshot attempt logged"
  Assert-Contains ($r5Retry.StdOut + $r5Retry.StdErr) "attempt 2/2" "second source snapshot retry executed even when delay=0"

  # 5a-pwfile: missing OCI_RESTIC_PASSWORD_FILE must STILL run local pre-expiry before failing
  [System.IO.File]::WriteAllText((Join-Path $data5 "a2.txt"), "expired-despite-missing-oci-pwfile")
  Assert-Eq 0 (Invoke-ResticDirect -Repo $dst5 -Password $dstPass -CacheDir $cache5 -ArgsList @("backup", "--tag", "gongchae-bell", "--time", (Format-ResticTime ($BASE_NOW - 21 * $DAY)), $data5)).ExitCode "c5 old snap 2"
  $env5PwMiss = Join-Path $c5 "src-pwfile-missing.env"
  Write-CaseEnv $env5PwMiss @{
    PC_RESTIC_REPOSITORY     = $dst5
    PC_RESTIC_PASSWORD_FILE  = $dstPwFile
    OCI_RESTIC_REPOSITORY    = $src2
    OCI_RESTIC_PASSWORD_FILE = (Join-Path $c5 "does-not-exist.pass")
    PC_LOCK_FILE             = (Join-Path $c5 "pc-pull.lock")
    PC_CACHE_DIR             = $cache5
    PC_LOG_DIR               = (Join-Path $c5 "logs")
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS    = "true"
    PC_ALLOW_LOCAL_SOURCE    = "true"
    PC_NOW_EPOCH_OVERRIDE    = "$BASE_NOW"
    COPY_MAX_RETRIES         = "0"
  }
  $r5PwMiss = Invoke-PcPull -Subcommand "run" -EnvPath $env5PwMiss
  Assert-True ($r5PwMiss.ExitCode -ne 0) "missing OCI password file must fail"
  Assert-Contains $r5PwMiss.StdOut "PRE_RETENTION_SUMMARY policy=wall-clock-14d forgotten=1 total=0 expire=0 keep=0" "local pre-expiry ran even with missing OCI password file"
  Assert-Contains ($r5PwMiss.StdOut + $r5PwMiss.StdErr) "LOCAL_RETENTION_OK_BUT_SOURCE_FAILED" "distinguished local retention ok vs missing OCI password file"
  Assert-Eq 0 @(Get-RepoSnapshots -Repo $dst5 -Password $dstPass -CacheDir $cache5 -Tag "gongchae-bell").Count "local expired snapshot deleted despite missing OCI password file"

  # 5a-samepw: identical PC and OCI passwords must be rejected
  $env5SamePw = Join-Path $c5 "same-pw.env"
  Write-CaseEnv $env5SamePw @{
    PC_RESTIC_REPOSITORY     = $dst5
    PC_RESTIC_PASSWORD_FILE  = $dstPwFile
    OCI_RESTIC_REPOSITORY    = $src2
    OCI_RESTIC_PASSWORD_FILE = $dstPwFile
    PC_LOCK_FILE             = (Join-Path $c5 "pc-pull.lock")
    PC_CACHE_DIR             = $cache5
    PC_LOG_DIR               = (Join-Path $c5 "logs")
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS    = "true"
    PC_ALLOW_LOCAL_SOURCE    = "true"
    PC_NOW_EPOCH_OVERRIDE    = "$BASE_NOW"
    COPY_MAX_RETRIES         = "0"
  }
  $r5SamePw = Invoke-PcPull -Subcommand "run" -EnvPath $env5SamePw
  Assert-True ($r5SamePw.ExitCode -ne 0) "identical PC and OCI passwords must fail"
  Assert-Contains ($r5SamePw.StdOut + $r5SamePw.StdErr) "PC_RESTIC_PASSWORD must be separate from OCI_RESTIC_PASSWORD" "separate password guard"

  # Local bad password -> explicit LOCAL_REPO_AUTH_FAIL
  $env5b = Join-Path $c5 "local-bad-pw.env"
  Write-CaseEnv $env5b @{
    PC_RESTIC_REPOSITORY     = $dst5
    PC_RESTIC_PASSWORD       = "wrong-local-pass-SECRET-ABC"
    OCI_RESTIC_REPOSITORY    = $src2
    OCI_RESTIC_PASSWORD_FILE = $srcPwFile
    PC_LOCK_FILE             = (Join-Path $c5 "pc-pull.lock")
    PC_CACHE_DIR             = $cache5
    PC_LOG_DIR               = (Join-Path $c5 "logs")
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS    = "true"
    PC_ALLOW_LOCAL_SOURCE    = "true"
  }
  $r5b = Invoke-PcPull -Subcommand "run" -EnvPath $env5b
  Assert-True ($r5b.ExitCode -ne 0) "bad local password must fail"
  Assert-Contains ($r5b.StdOut + $r5b.StdErr) "LOCAL_REPO_AUTH_FAIL" "explicit local auth failure code"

  # ---------------------------------------------------------------------------
  Write-Host "=== [6/10] Destination lock preserved even with --no-lock ==="
  $c6 = Join-Path $TMP_ROOT "case6"
  $dst6 = Join-Path $c6 "dst-pc"
  $cache6 = Join-Path $c6 "cache"
  New-Item -ItemType Directory -Path $dst6, $cache6 -Force | Out-Null
  Assert-Eq 0 (Invoke-ResticDirect -Repo $dst6 -Password $dstPass -CacheDir $cache6 -ArgsList @("init")).ExitCode "c6 init"
  # Make dst6/locks read-only so any lock write attempt on destination fails!
  $dst6Locks = Join-Path $dst6 "locks"
  Set-ReadOnlyTreeAcl $dst6Locks
  $env6 = Join-Path $c6 "dst-lock.env"
  Write-CaseEnv $env6 @{
    PC_RESTIC_REPOSITORY     = $dst6
    PC_RESTIC_PASSWORD_FILE  = $dstPwFile
    OCI_RESTIC_REPOSITORY    = $src2
    OCI_RESTIC_PASSWORD_FILE = $srcPwFile
    PC_LOCK_FILE             = (Join-Path $c6 "pc-pull.lock")
    PC_CACHE_DIR             = $cache6
    PC_LOG_DIR               = (Join-Path $c6 "logs")
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS    = "true"
    PC_ALLOW_LOCAL_SOURCE    = "true"
    PC_NOW_EPOCH_OVERRIDE    = "$BASE_NOW"
    COPY_MAX_RETRIES         = "0"
  }
  $r6 = Invoke-PcPull -Subcommand "run" -EnvPath $env6
  Assert-True ($r6.ExitCode -ne 0) "read-only destination locks must fail (destination lock is enforced)"
  Restore-WritableTreeAcl $dst6Locks

  # ---------------------------------------------------------------------------
  Write-Host "=== [7/10] Copy partial failure / source prune race -> post-copy prune runs & fails closed ==="
  $c7 = Join-Path $TMP_ROOT "case7"
  $src7 = Join-Path $c7 "src-race"
  $dst7 = Join-Path $c7 "dst-pc"
  $srcCache7 = Join-Path $c7 "src-cache"
  $pcCache7 = Join-Path $c7 "pc-cache"
  $data7 = Join-Path $c7 "data"
  New-Item -ItemType Directory -Path $src7, $dst7, $srcCache7, $pcCache7, $data7 -Force | Out-Null
  Assert-Eq 0 (Invoke-ResticDirect -Repo $src7 -Password $srcPass -CacheDir $srcCache7 -ArgsList @("init")).ExitCode "c7 src init"
  Assert-Eq 0 (Invoke-ResticDirect -Repo $dst7 -Password $dstPass -CacheDir $pcCache7 -ArgsList @("init")).ExitCode "c7 dst init"
  [System.IO.File]::WriteAllText((Join-Path $data7 "p.txt"), "race-payload-unique-content-999999")
  Assert-Eq 0 (Invoke-ResticDirect -Repo $src7 -Password $srcPass -CacheDir $srcCache7 -ArgsList @("backup", "--tag", "gongchae-bell", "--time", (Format-ResticTime ($BASE_NOW - 2 * $DAY)), $data7)).ExitCode "c7 src backup"
  # Simulate OCI prune removing source data packs mid-copy (remove all data packs and use a separate PC cache
  # so result never depends on filesystem pack hash ordering or cached tree blobs).
  $packFiles = @(Get-ChildItem -Path (Join-Path $src7 "data") -Recurse -File)
  Assert-True ($packFiles.Count -gt 0) "src7 must have data pack"
  foreach ($pf in $packFiles) {
    Remove-Item -LiteralPath $pf.FullName -Force
  }
  Set-ReadOnlyTreeAcl $src7

  $env7 = Join-Path $c7 "race.env"
  Write-CaseEnv $env7 @{
    PC_RESTIC_REPOSITORY     = $dst7
    PC_RESTIC_PASSWORD_FILE  = $dstPwFile
    OCI_RESTIC_REPOSITORY    = $src7
    OCI_RESTIC_PASSWORD_FILE = $srcPwFile
    HEALTHCHECKS_PC_PULL_URL = $hbServer.Url
    PC_LOCK_FILE             = (Join-Path $c7 "pc-pull.lock")
    PC_CACHE_DIR             = $pcCache7
    PC_LOG_DIR               = (Join-Path $c7 "logs")
    RESTIC_BIN               = $ResticBin
    PYTHON_BIN               = $PythonBin
    EXPIRE_SELECT_PATH       = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS    = "true"
    PC_ALLOW_LOCAL_SOURCE    = "true"
    PC_NOW_EPOCH_OVERRIDE    = "$BASE_NOW"
    COPY_MAX_RETRIES         = "1"
    COPY_RETRY_DELAY_SECONDS = "0"
  }
  if (Test-Path $hbLog) { Remove-Item $hbLog -Force }
  $r7 = Invoke-PcPull -Subcommand "run" -EnvPath $env7
  Assert-True ($r7.ExitCode -ne 0) "source prune race must fail (never hide as success)"
  Assert-Contains ($r7.StdOut + $r7.StdErr) "SOURCE_COPY_FAIL" "copy failure recorded"
  Assert-Contains $r7.StdOut "POST_RETENTION_SUMMARY policy=wall-clock-14d forgotten=0 total=0 expire=0 keep=0" "post-copy prune ran after copy failure"
  Assert-Eq 0 @(Get-RepoSnapshots -Repo $dst7 -Password $dstPass -CacheDir $pcCache7 -Tag "gongchae-bell").Count "no partial snapshot saved in dst"
  Assert-Contains ([System.IO.File]::ReadAllText($hbLog)) "/deadbeef-pc-token-9999/fail|" "/fail ping sent on copy race"

  # ---------------------------------------------------------------------------
  Write-Host "=== [8/10] Snapshot crossing 14-day boundary DURING copy -> post-copy retention deletes it ==="
  $c8 = Join-Path $TMP_ROOT "case8"
  $src8 = Join-Path $c8 "src-boundary"
  $dst8 = Join-Path $c8 "dst-pc"
  $cache8 = Join-Path $c8 "cache"
  $data8 = Join-Path $c8 "data"
  New-Item -ItemType Directory -Path $src8, $dst8, $cache8, $data8 -Force | Out-Null
  Assert-Eq 0 (Invoke-ResticDirect -Repo $src8 -Password $srcPass -CacheDir $cache8 -ArgsList @("init")).ExitCode "c8 src init"
  Assert-Eq 0 (Invoke-ResticDirect -Repo $dst8 -Password $dstPass -CacheDir $cache8 -ArgsList @("init")).ExitCode "c8 dst init"
  # Snapshot is exactly 14d old at PRE ($BASE_NOW), so it is eligible for copy; at POST ($BASE_NOW + 2s) it is 14d+2s -> MUST be deleted!
  [System.IO.File]::WriteAllText((Join-Path $data8 "b.txt"), "crosses-14d-during-copy")
  Assert-Eq 0 (Invoke-ResticDirect -Repo $src8 -Password $srcPass -CacheDir $cache8 -ArgsList @("backup", "--tag", "gongchae-bell", "--time", (Format-ResticTime ($BASE_NOW - $WINDOW)), $data8)).ExitCode "c8 src snap"
  Set-ReadOnlyTreeAcl $src8

  $env8 = Join-Path $c8 "cross.env"
  Write-CaseEnv $env8 @{
    PC_RESTIC_REPOSITORY       = $dst8
    PC_RESTIC_PASSWORD_FILE    = $dstPwFile
    OCI_RESTIC_REPOSITORY      = $src8
    OCI_RESTIC_PASSWORD_FILE   = $srcPwFile
    PC_LOCK_FILE               = (Join-Path $c8 "pc-pull.lock")
    PC_CACHE_DIR               = $cache8
    PC_LOG_DIR                 = (Join-Path $c8 "logs")
    RESTIC_BIN                 = $ResticBin
    PYTHON_BIN                 = $PythonBin
    EXPIRE_SELECT_PATH         = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS      = "true"
    PC_ALLOW_LOCAL_SOURCE      = "true"
    PC_NOW_EPOCH_OVERRIDE      = "$BASE_NOW"
    PC_POST_NOW_EPOCH_OVERRIDE = "$($BASE_NOW + 2)"
    COPY_MAX_RETRIES           = "0"
  }
  $r8 = Invoke-PcPull -Subcommand "run" -EnvPath $env8
  Assert-Eq 0 $r8.ExitCode "boundary-crossing run must succeed cleanly: $($r8.StdOut) $($r8.StdErr)"
  Assert-Contains $r8.StdOut "SOURCE_COPY_OK candidates=1" "copied while still <=14d"
  Assert-Contains $r8.StdOut "POST_RETENTION_SUMMARY policy=wall-clock-14d forgotten=1 total=0 expire=0 keep=0" "post-copy retention deleted newly expired copy"
  Assert-Eq 0 @(Get-RepoSnapshots -Repo $dst8 -Password $dstPass -CacheDir $cache8 -Tag "gongchae-bell").Count "0 copies left after boundary-crossing post-cleanup"

  # ---------------------------------------------------------------------------
  Write-Host "=== [9/10] Single-instance OS lock exclusion (LOCK_HELD, exit 20) ==="
  $lock9 = Join-Path $c8 "pc-pull.lock"
  $heldStream = [System.IO.File]::Open($lock9, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
  try {
    $r9 = Invoke-PcPull -Subcommand "run" -EnvPath $env8
    Assert-Eq 20 $r9.ExitCode "concurrent run must exit 20"
    Assert-Contains ($r9.StdOut + $r9.StdErr) "LOCK_HELD" "LOCK_HELD logged"
  } finally {
    $heldStream.Close()
    $heldStream.Dispose()
  }

  # ---------------------------------------------------------------------------
  Write-Host "=== [10/10] Secret redaction across stdout, stderr, log file, and /fail heartbeat ==="
  $c10 = Join-Path $TMP_ROOT "case10"
  New-Item -ItemType Directory -Path $c10 -Force | Out-Null
  $secLocal = 'L0c@l$ecret+P[R]1!'
  $secOci   = '0ci$ecret&Key(2)!'
  $secAk    = 'MOCK_ACCESS_KEY_987654'
  $secSk    = 'MOCK_SECRET_KEY_VALUE_1234567890'
  # 10a: Put secret with regex metacharacters in missing local repo path so restic echoes it on stderr.
  $env10 = Join-Path $c10 "redact.env"
  Write-CaseEnv $env10 @{
    PC_RESTIC_REPOSITORY      = (Join-Path $c10 "dst-missing-$secLocal")
    PC_RESTIC_PASSWORD        = $secLocal
    OCI_RESTIC_REPOSITORY     = "s3:https://user:$secSk@example.invalid/bucket"
    OCI_RESTIC_PASSWORD       = $secOci
    OCI_AWS_ACCESS_KEY_ID     = $secAk
    OCI_AWS_SECRET_ACCESS_KEY = $secSk
    HEALTHCHECKS_PC_PULL_URL  = $hbServer.Url
    PC_LOCK_FILE              = (Join-Path $c10 "pc-pull.lock")
    PC_CACHE_DIR              = (Join-Path $c10 "cache")
    PC_LOG_DIR                = (Join-Path $c10 "logs")
    RESTIC_BIN                = $ResticBin
    PYTHON_BIN                = $PythonBin
    EXPIRE_SELECT_PATH        = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS     = "true"
  }
  if (Test-Path $hbLog) { Remove-Item $hbLog -Force }
  $r10 = Invoke-PcPull -Subcommand "run" -EnvPath $env10
  Assert-True ($r10.ExitCode -ne 0) "case10a expected failure"
  $combined10 = $r10.StdOut + "`n" + $r10.StdErr
  $logFile10 = Join-Path $c10 "logs\pc-pull.log"
  if (Test-Path $logFile10) { $combined10 += "`n" + [System.IO.File]::ReadAllText($logFile10) }
  $hb10 = if (Test-Path $hbLog) { [System.IO.File]::ReadAllText($hbLog) } else { "" }
  foreach ($s in @($secLocal, $secOci, $secAk, $secSk, "deadbeef-pc-token-9999")) {
    Assert-NotContains $combined10 $s "secret must be redacted from stdout/stderr/log (10a)"
    foreach ($hbLine in ($hb10 -split "`r?`n" | Where-Object { $_ -like "*|*" })) {
      $bodyPart = $hbLine.Substring($hbLine.IndexOf("|") + 1)
      Assert-NotContains $bodyPart $s "secret must be redacted from heartbeat body (10a)"
    }
  }
  Assert-Contains $combined10 "[REDACTED]" "redaction marker present in local error output (10a)"

  # 10b: Valid local repo + missing source path embedding OCI secrets -> verify OCI secrets & URL creds redacted in logs and /fail body.
  $dst10b = Join-Path $c10 "dst-valid"
  New-Item -ItemType Directory -Path $dst10b -Force | Out-Null
  Assert-Eq 0 (Invoke-ResticDirect -Repo $dst10b -Password $secLocal -CacheDir (Join-Path $c10 "cache") -ArgsList @("init")).ExitCode "c10b init"
  $env10b = Join-Path $c10 "redact-src.env"
  Write-CaseEnv $env10b @{
    PC_RESTIC_REPOSITORY      = $dst10b
    PC_RESTIC_PASSWORD        = $secLocal
    OCI_RESTIC_REPOSITORY     = (Join-Path $c10 "missing-src-$secOci-$secAk-$secSk")
    OCI_RESTIC_PASSWORD       = $secOci
    OCI_AWS_ACCESS_KEY_ID     = $secAk
    OCI_AWS_SECRET_ACCESS_KEY = $secSk
    HEALTHCHECKS_PC_PULL_URL  = $hbServer.Url
    PC_LOCK_FILE              = (Join-Path $c10 "pc-pull.lock")
    PC_CACHE_DIR              = (Join-Path $c10 "cache")
    PC_LOG_DIR                = (Join-Path $c10 "logs10b")
    RESTIC_BIN                = $ResticBin
    PYTHON_BIN                = $PythonBin
    EXPIRE_SELECT_PATH        = $EXPIRE_SELECT
    PC_ALLOW_CUSTOM_PATHS     = "true"
    PC_ALLOW_LOCAL_SOURCE     = "true"
    COPY_MAX_RETRIES          = "0"
  }
  if (Test-Path $hbLog) { Remove-Item $hbLog -Force }
  $r10b = Invoke-PcPull -Subcommand "run" -EnvPath $env10b
  Assert-True ($r10b.ExitCode -ne 0) "case10b expected failure"
  $combined10b = $r10b.StdOut + "`n" + $r10b.StdErr
  $logFile10b = Join-Path $c10 "logs10b\pc-pull.log"
  if (Test-Path $logFile10b) { $combined10b += "`n" + [System.IO.File]::ReadAllText($logFile10b) }
  $hb10b = if (Test-Path $hbLog) { [System.IO.File]::ReadAllText($hbLog) } else { "" }
  foreach ($s in @($secLocal, $secOci, $secAk, $secSk, "deadbeef-pc-token-9999")) {
    Assert-NotContains $combined10b $s "secret must be redacted from stdout/stderr/log (10b)"
    foreach ($hbLine in ($hb10b -split "`r?`n" | Where-Object { $_ -like "*|*" })) {
      $bodyPart = $hbLine.Substring($hbLine.IndexOf("|") + 1)
      Assert-NotContains $bodyPart $s "secret must be redacted from heartbeat body (10b)"
    }
  }
  Assert-Contains $combined10b "[REDACTED]" "redaction marker present in source error output (10b)"
  Assert-Contains $hb10b "/deadbeef-pc-token-9999/fail|" "/fail heartbeat sent on 10b"

  Write-Host "ALL_PC_PULL_TESTS_PASSED"
} finally {
  if ($null -ne $hbServer -and $null -ne $hbServer.Process) {
    try { $hbServer.Process.Kill() } catch {}
    try { $hbServer.Process.Dispose() } catch {}
  }
  foreach ($roDir in $script:ReadOnlyDirsToRestore) {
    Restore-WritableTreeAcl $roDir
  }
  if (Test-Path -LiteralPath $TMP_ROOT) {
    Remove-Item -LiteralPath $TMP_ROOT -Recurse -Force -ErrorAction SilentlyContinue
  }
}
