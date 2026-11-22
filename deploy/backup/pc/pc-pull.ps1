<#
.SYNOPSIS
  Gongchae-Bell Windows PC pull backup & wall-clock 14-day retention script (ADR-0012, ADR-0017, #17).
.DESCRIPTION
  - Native Windows PowerShell 5.1+ / PowerShell 7+ (no Docker Desktop required; uses native restic 0.19.1 + Python 3).
  - Reuses the shared deploy/backup/expire-select.py selector (zero parser drift with OCI server backup.sh).
  - Strict role separation: local forget/prune commands NEVER receive OCI credentials or OCI repository URL.
  - Order on `run`:
      1. Acquire single-instance OS exclusive file lock (exit 20 on LOCK_HELD).
      2. Local pre-expire (>14d UTC by explicit 64-hex ID) -> prune --max-unused 0 -> re-verify 0 expired
         BEFORE any network/OCI credential check (0 remaining snapshots allowed per user decision 04).
      3. Read-only OCI source snapshot listing (`--no-lock`) -> validate full JSON & keep only <=14d 64-hex IDs.
      4. Copy only valid non-expired IDs (`restic --no-lock -r <LOCAL_DST> copy --from-repo <OCI_SRC> <ids...>`);
         destination append lock stays enforced by restic. Bounded retry on transient/source-prune race.
      5. Local post-copy retention & prune --max-unused 0 at post-transfer UTC epoch (always runs after copy
         success OR failure so boundary-crossed snapshots and orphaned packs are cleaned up).
      6. Send Healthchecks.io success ping ONLY when pre-retention, source sync, and post-retention all succeed;
         send `/fail` ping with redacted summary on any failure.
#>
[CmdletBinding()]
param(
  [ValidateSet("run", "init", "snapshots")]
  [string]$Command = "run",

  [string]$EnvFile = "",

  [switch]$StandaloneInit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RETENTION_SECONDS = 14 * 86400
$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path

$script:SecretValues = @()
$script:LogFilePath = ""
$script:LockStream = $null
$script:InRun = $false
$script:RunSucceeded = $false
$script:FailReason = ""
$script:HealthchecksUrl = ""

function Get-UtcIsoNow {
  return [DateTimeOffset]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
}

function Get-UtcEpochNow {
  return [long][DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
}

function Register-Secret {
  param([string]$Value)
  if ([string]::IsNullOrEmpty($Value)) { return }
  if (@($script:SecretValues) -notcontains $Value) {
    $script:SecretValues = @(@($script:SecretValues) + @($Value) | Sort-Object { $_.Length } -Descending)
  }
}

function Register-HealthchecksSecret {
  param([string]$Url)
  if ([string]::IsNullOrWhiteSpace($Url)) { return }
  $trimmed = $Url.TrimEnd("/")
  Register-Secret $Url
  Register-Secret $trimmed
  $idx = $trimmed.LastIndexOf("/")
  if ($idx -ge 0 -and $idx -lt ($trimmed.Length - 1)) {
    $token = $trimmed.Substring($idx + 1)
    if ($token.Length -ge 6) {
      Register-Secret $token
    }
  }
}

function Redact-Text {
  param([AllowNull()][string]$Text)
  if ([string]::IsNullOrEmpty($Text)) { return "" }

  $result = $Text
  if (@($script:SecretValues).Count -gt 0) {
    $sb = New-Object System.Text.StringBuilder
    $pos = 0
    $len = $result.Length
    while ($pos -lt $len) {
      $bestIdx = -1
      $bestLen = 0
      foreach ($sec in $script:SecretValues) {
        if ([string]::IsNullOrEmpty($sec)) { continue }
        $found = $result.IndexOf($sec, $pos, [System.StringComparison]::Ordinal)
        if ($found -ge 0) {
          if ($bestIdx -lt 0 -or $found -lt $bestIdx -or ($found -eq $bestIdx -and $sec.Length -gt $bestLen)) {
            $bestIdx = $found
            $bestLen = $sec.Length
          }
        }
      }
      if ($bestIdx -lt 0) {
        [void]$sb.Append($result.Substring($pos))
        break
      }
      if ($bestIdx -gt $pos) {
        [void]$sb.Append($result.Substring($pos, $bestIdx - $pos))
      }
      [void]$sb.Append("[REDACTED]")
      $pos = $bestIdx + $bestLen
    }
    $result = $sb.ToString()
  }

  $result = [regex]::Replace($result, '(https?://)[^\s@/:]+:[^\s@/:]+@', '$1[REDACTED]@')
  $result = [regex]::Replace($result, 'https?://[^\s/]*hc-ping[^\s]*', '[REDACTED_HC_URL]')
  return $result
}

function Write-Log {
  param(
    [string]$Message,
    [switch]$IsError
  )
  $redacted = Redact-Text $Message
  $line = "[{0}] {1}" -f (Get-UtcIsoNow), $redacted
  if ($IsError) {
    [Console]::Error.WriteLine($line)
  } else {
    [Console]::Out.WriteLine($line)
  }
  if (-not [string]::IsNullOrEmpty($script:LogFilePath)) {
    try {
      $logDir = Split-Path -Parent $script:LogFilePath
      if (-not (Test-Path -LiteralPath $logDir)) {
        New-Item -ItemType Directory -Path $logDir -Force | Out-Null
      }
      [System.IO.File]::AppendAllText($script:LogFilePath, $line + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    } catch {
      # Never fail redaction/logging silently into unredacted output.
    }
  }
}

function Fail-Run {
  param(
    [string]$Reason,
    [int]$ExitCode = 1
  )
  $redacted = Redact-Text $Reason
  $script:FailReason = $redacted
  Write-Log ("ERROR: " + $redacted) -IsError
  $ex = New-Object System.Exception($redacted)
  $ex.Data["ExitCode"] = $ExitCode
  throw $ex
}

function Format-WinArg {
  param([string]$Arg)
  if ($null -eq $Arg) { return '""' }
  if ($Arg.Length -eq 0) { return '""' }
  if ($Arg -notmatch '[\s"]') { return $Arg }
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.Append('"')
  $bsCount = 0
  for ($i = 0; $i -lt $Arg.Length; $i++) {
    $ch = $Arg[$i]
    if ($ch -eq '\') {
      $bsCount++
    } elseif ($ch -eq '"') {
      [void]$sb.Append('\', $bsCount * 2 + 1)
      [void]$sb.Append('"')
      $bsCount = 0
    } else {
      if ($bsCount -gt 0) {
        [void]$sb.Append('\', $bsCount)
        $bsCount = 0
      }
      [void]$sb.Append($ch)
    }
  }
  if ($bsCount -gt 0) {
    [void]$sb.Append('\', $bsCount * 2)
  }
  [void]$sb.Append('"')
  return $sb.ToString()
}

function Invoke-ExternalProcess {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [string[]]$Arguments = @(),
    [hashtable]$SetEnv = @{},
    [string[]]$RemoveEnv = @(),
    [AllowNull()][string]$StdinText = $null
  )
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $FilePath
  $psi.Arguments = (($Arguments | ForEach-Object { Format-WinArg $_ }) -join " ")
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.RedirectStandardInput = ($null -ne $StdinText)
  $psi.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
  $psi.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)

  foreach ($k in $RemoveEnv) {
    if ($psi.EnvironmentVariables.ContainsKey($k)) {
      $psi.EnvironmentVariables.Remove($k)
    }
  }
  foreach ($k in $SetEnv.Keys) {
    $val = $SetEnv[$k]
    if ($null -eq $val) {
      if ($psi.EnvironmentVariables.ContainsKey($k)) { $psi.EnvironmentVariables.Remove($k) }
    } else {
      $psi.EnvironmentVariables[$k] = [string]$val
    }
  }

  $proc = New-Object System.Diagnostics.Process
  $proc.StartInfo = $psi
  if (-not $proc.Start()) {
    throw "PROCESS_START_FAIL unable to start $FilePath"
  }

  $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
  $stderrTask = $proc.StandardError.ReadToEndAsync()

  if ($null -ne $StdinText) {
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($StdinText)
    $proc.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $proc.StandardInput.BaseStream.Flush()
    $proc.StandardInput.Close()
  }

  $proc.WaitForExit()
  $stdout = $stdoutTask.GetAwaiter().GetResult()
  $stderr = $stderrTask.GetAwaiter().GetResult()
  $exitCode = $proc.ExitCode
  $proc.Dispose()

  return [pscustomobject]@{
    ExitCode = $exitCode
    StdOut   = $stdout
    StdErr   = $stderr
  }
}

function Write-RedactedOutput {
  param([string]$Text, [switch]$IsError)
  if ([string]::IsNullOrWhiteSpace($Text)) { return }
  foreach ($rawLine in ($Text -split "`r?`n")) {
    if ($rawLine.Length -gt 0) {
      Write-Log $rawLine -IsError:$IsError
    }
  }
}

function Send-Heartbeat {
  param(
    [string]$Suffix,
    [string]$Body
  )
  if ([string]::IsNullOrWhiteSpace($script:HealthchecksUrl)) {
    $kind = if ([string]::IsNullOrEmpty($Suffix)) { "ok" } else { $Suffix.TrimStart("/") }
    Write-Log "HEARTBEAT_SKIPPED no HEALTHCHECKS_PC_PULL_URL configured (kind=$kind)"
    return $true
  }

  $url = $script:HealthchecksUrl.TrimEnd("/") + $Suffix
  $safeBody = Redact-Text $Body
  try {
    $params = @{
      Uri             = $url
      Method          = "Post"
      Body            = [System.Text.UTF8Encoding]::new($false).GetBytes($safeBody)
      ContentType     = "text/plain; charset=utf-8"
      TimeoutSec      = 10
      UseBasicParsing = $true
    }
    $null = Invoke-WebRequest @params
    return $true
  } catch {
    Write-Log ("HEARTBEAT_HTTP_ERROR kind={0} detail={1}" -f ($(if ($Suffix) { $Suffix } else { "ok" }), $_.Exception.Message)) -IsError
    return $false
  }
}

function Test-IsReparsePoint {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) { return $false }
  $item = Get-Item -LiteralPath $Path -Force
  return (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)
}

function Assert-RegularFileNoSymlink {
  param([string]$Path, [string]$Label)
  if (-not (Test-Path -LiteralPath $Path)) {
    Fail-Run "ENV_FILE_MISSING $Label does not exist: $Path"
  }
  if (Test-IsReparsePoint $Path) {
    Fail-Run "ENV_PERM_FAIL $Label must not be a symlink or reparse point: $Path"
  }
  $item = Get-Item -LiteralPath $Path -Force
  if ($item.PSIsContainer) {
    Fail-Run "ENV_PERM_FAIL $Label must be a regular file, not a directory: $Path"
  }
}

function Assert-RestrictedWindowsAcl {
  param([string]$Path, [string]$Label)
  if ($env:OS -ne "Windows_NT") { return }

  $currentUserSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
  $allowedSids = @(
    $currentUserSid.Value,
    (New-Object System.Security.Principal.SecurityIdentifier([System.Security.Principal.WellKnownSidType]::LocalSystemSid, $null)).Value,
    (New-Object System.Security.Principal.SecurityIdentifier([System.Security.Principal.WellKnownSidType]::BuiltinAdministratorsSid, $null)).Value,
    (New-Object System.Security.Principal.SecurityIdentifier([System.Security.Principal.WellKnownSidType]::CreatorOwnerSid, $null)).Value
  )

  $acl = [System.IO.File]::GetAccessControl($Path)
  $rules = $acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])
  $badPrincipals = @()
  foreach ($rule in $rules) {
    if ($rule.AccessControlType -ne [System.Security.AccessControl.AccessControlType]::Allow) { continue }
    $sidVal = $rule.IdentityReference.Value
    if ($allowedSids -notcontains $sidVal) {
      $display = $sidVal
      try {
        $display = $rule.IdentityReference.Translate([System.Security.Principal.NTAccount]).Value
      } catch {
        # Keep raw SID when unmapped on host.
      }
      if ($badPrincipals -notcontains $display) {
        $badPrincipals += $display
      }
    }
  }
  if (@($badPrincipals).Count -gt 0) {
    Fail-Run ("ENV_ACL_FAIL $Label grants Allow access to non-restricted principal(s): {0} (use icacls /inheritance:r to restrict to current user, SYSTEM, Administrators)" -f ($badPrincipals -join ", "))
  }
}

function Normalize-LocalPath {
  param([string]$Path)
  $expanded = [Environment]::ExpandEnvironmentVariables($Path.Trim())
  $full = [System.IO.Path]::GetFullPath($expanded)
  return $full.TrimEnd('\', '/')
}

function Test-HasRemoteBackendScheme {
  param([string]$Repo)
  if ([string]::IsNullOrWhiteSpace($Repo)) { return $false }
  # UNC network shares (\\server\share or //server/share) are remote.
  if ($Repo -match '^(\\\\|//)') { return $true }
  # Windows drive letters (C:\ or C:/) are local paths; 2+ letter schemes (s3:, rest:, sftp:, rclone:, etc.) are remote.
  if ($Repo -match '^[a-zA-Z]:[\\/]') { return $false }
  return ($Repo -match '^[a-zA-Z][a-zA-Z0-9+.-]+:')
}

function Assert-SafeLocalDestination {
  param([string]$RepoPath, [bool]$AllowCustomPaths)
  if ([string]::IsNullOrWhiteSpace($RepoPath)) {
    Fail-Run "CONFIG_INVALID PC_RESTIC_REPOSITORY is empty"
  }
  if (Test-HasRemoteBackendScheme $RepoPath) {
    Fail-Run "DEST_MUST_BE_LOCAL PC_RESTIC_REPOSITORY must be a local filesystem path, refusing remote target: $RepoPath"
  }
  $norm = Normalize-LocalPath $RepoPath
  if (Test-IsReparsePoint $norm) {
    Fail-Run "DEST_REPARSE_POINT_FORBIDDEN refusing symlink/junction destination: $norm"
  }
  if ($AllowCustomPaths) { return $norm }

  # Block OneDrive sync directories
  foreach ($odVar in @($env:OneDrive, $env:OneDriveConsumer, $env:OneDriveCommercial)) {
    if (-not [string]::IsNullOrWhiteSpace($odVar)) {
      $odNorm = Normalize-LocalPath $odVar
      if ($norm.Equals($odNorm, [System.StringComparison]::OrdinalIgnoreCase) -or
          $norm.StartsWith($odNorm + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
        Fail-Run "DEST_SYNC_DIR_FORBIDDEN refusing destination inside OneDrive: $norm"
      }
    }
  }

  # Block Google Drive path, network drives, and non-NTFS volumes (NTFS required for Windows ACL enforcement)
  if ($norm -match '(?i)(^|[\\/])google\s*drive([\\/]|$)') {
    Fail-Run "DEST_SYNC_DIR_FORBIDDEN refusing destination inside Google Drive: $norm"
  }
  if ($env:OS -eq "Windows_NT") {
    $rootDrive = [System.IO.Path]::GetPathRoot($norm)
    if (-not [string]::IsNullOrWhiteSpace($rootDrive)) {
      try {
        $di = New-Object System.IO.DriveInfo($rootDrive)
        if ($di.DriveType -eq [System.IO.DriveType]::Network) {
          Fail-Run "DEST_NETWORK_DRIVE_FORBIDDEN refusing destination on network drive ($rootDrive): $norm"
        }
        if ($di.IsReady) {
          if ($di.VolumeLabel -match '(?i)google\s*drive') {
            Fail-Run "DEST_SYNC_DIR_FORBIDDEN refusing destination on Google Drive volume ($rootDrive): $norm"
          }
          if (-not $di.DriveFormat.Equals("NTFS", [System.StringComparison]::OrdinalIgnoreCase)) {
            Fail-Run "DEST_NON_NTFS_FORBIDDEN refusing destination on non-NTFS volume ($rootDrive format=$($di.DriveFormat)): $norm"
          }
        }
      } catch {
        if ($_.Exception.Message -match '^DEST_') { throw }
        # Ignore unmounted or non-drive-letter edge cases.
      }
    }
  }

  # Block Git checkout / Orca worktree directories
  $probe = $norm
  while (-not [string]::IsNullOrWhiteSpace($probe)) {
    $gitMarker = Join-Path $probe ".git"
    if (Test-Path -LiteralPath $gitMarker) {
      Fail-Run "DEST_GIT_REPO_FORBIDDEN refusing destination inside a Git checkout/worktree ($probe): $norm"
    }
    $parent = Split-Path -Parent $probe
    if ($parent -eq $probe) { break }
    $probe = $parent
  }

  return $norm
}

function Read-PasswordFile {
  param([string]$Path, [string]$Label, [bool]$CheckAcl)
  $expanded = [Environment]::ExpandEnvironmentVariables($Path.Trim())
  Assert-RegularFileNoSymlink $expanded $Label
  if ($CheckAcl) {
    Assert-RestrictedWindowsAcl $expanded $Label
  }
  $raw = [System.IO.File]::ReadAllText($expanded, [System.Text.UTF8Encoding]::new($false))
  $val = $raw -replace "(`r`n|`n|`r)$", ""
  if ([string]::IsNullOrEmpty($val)) {
    Fail-Run "CONFIG_INVALID $Label is empty: $expanded"
  }
  return $val
}

function Load-Config {
  param([string]$Path)
  $resolvedEnv = $Path
  if ([string]::IsNullOrWhiteSpace($resolvedEnv)) {
    if (-not [string]::IsNullOrWhiteSpace($env:PC_PULL_ENV_FILE)) {
      $resolvedEnv = $env:PC_PULL_ENV_FILE
    } else {
      $localApp = if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { $env:LOCALAPPDATA } else { $env:HOME }
      $resolvedEnv = Join-Path $localApp "gongchae-bell\backup-env\pc-pull.env"
    }
  }
  $resolvedEnv = [Environment]::ExpandEnvironmentVariables($resolvedEnv)
  Assert-RegularFileNoSymlink $resolvedEnv "PC pull env file"

  $kv = @{}
  $lineNo = 0
  foreach ($rawLine in [System.IO.File]::ReadAllLines($resolvedEnv, [System.Text.UTF8Encoding]::new($false))) {
    $lineNo++
    $line = $rawLine.TrimEnd("`r")
    if ($line -match '^\s*(#|$)') { continue }
    if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
      Fail-Run "CONFIG_INVALID invalid line $lineNo in $resolvedEnv"
    }
    $key = $Matches[1]
    $val = $Matches[2]
    if ($val -match "^'(.*)'$" -or $val -match '^"(.*)"$') {
      $val = $Matches[1]
    }
    $kv[$key] = $val
  }

  # Register any inline secrets immediately so subsequent validation failures redact them.
  foreach ($secKey in @("PC_RESTIC_PASSWORD", "OCI_RESTIC_PASSWORD", "OCI_AWS_ACCESS_KEY_ID", "OCI_AWS_SECRET_ACCESS_KEY")) {
    if ($kv.ContainsKey($secKey) -and -not [string]::IsNullOrEmpty($kv[$secKey])) {
      Register-Secret $kv[$secKey]
    }
  }
  if ($kv.ContainsKey("HEALTHCHECKS_PC_PULL_URL") -and -not [string]::IsNullOrWhiteSpace($kv["HEALTHCHECKS_PC_PULL_URL"])) {
    $script:HealthchecksUrl = $kv["HEALTHCHECKS_PC_PULL_URL"].Trim().TrimEnd("/")
    Register-HealthchecksSecret $script:HealthchecksUrl
  }

  # Reject removed count-based retention knob (ADR-0017 parity with backup.sh).
  if ($kv.ContainsKey("BACKUP_KEEP_DAILY") -and -not [string]::IsNullOrWhiteSpace($kv["BACKUP_KEEP_DAILY"])) {
    Fail-Run "CONFIG_INVALID BACKUP_KEEP_DAILY is no longer supported; retention is fixed at 14 days from run UTC time (ADR-0017)"
  }

  $checkAcl = $true
  if ($kv.ContainsKey("PC_CHECK_ACL") -and -not [string]::IsNullOrWhiteSpace($kv["PC_CHECK_ACL"])) {
    $checkAcl = ($kv["PC_CHECK_ACL"].Trim().ToLowerInvariant() -ne "false")
  }
  if ($checkAcl) {
    Assert-RestrictedWindowsAcl $resolvedEnv "PC pull env file"
  }

  $allowCustomPaths = ($kv.ContainsKey("PC_ALLOW_CUSTOM_PATHS") -and $kv["PC_ALLOW_CUSTOM_PATHS"].Trim().ToLowerInvariant() -eq "true")
  $allowLocalSource = ($kv.ContainsKey("PC_ALLOW_LOCAL_SOURCE") -and $kv["PC_ALLOW_LOCAL_SOURCE"].Trim().ToLowerInvariant() -eq "true")

  $localApp = if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { $env:LOCALAPPDATA } else { $env:HOME }
  $baseDir = Join-Path $localApp "gongchae-bell"

  $pcRepoRaw = if ($kv.ContainsKey("PC_RESTIC_REPOSITORY") -and -not [string]::IsNullOrWhiteSpace($kv["PC_RESTIC_REPOSITORY"])) {
    $kv["PC_RESTIC_REPOSITORY"]
  } else {
    Join-Path $baseDir "backup-replica"
  }
  $pcRepo = Assert-SafeLocalDestination $pcRepoRaw $allowCustomPaths

  # Resolve local PC password (file or inline, never both)
  $pcPwFile = if ($kv.ContainsKey("PC_RESTIC_PASSWORD_FILE")) { $kv["PC_RESTIC_PASSWORD_FILE"].Trim() } else { "" }
  $pcPwInline = if ($kv.ContainsKey("PC_RESTIC_PASSWORD")) { $kv["PC_RESTIC_PASSWORD"] } else { "" }
  if (-not [string]::IsNullOrEmpty($pcPwFile) -and -not [string]::IsNullOrEmpty($pcPwInline)) {
    Fail-Run "CONFIG_INVALID set either PC_RESTIC_PASSWORD_FILE or PC_RESTIC_PASSWORD, not both"
  }
  $pcPassword = if (-not [string]::IsNullOrEmpty($pcPwFile)) {
    Read-PasswordFile $pcPwFile "PC_RESTIC_PASSWORD_FILE" $checkAcl
  } else {
    $pcPwInline
  }
  if ([string]::IsNullOrEmpty($pcPassword)) {
    Fail-Run "CONFIG_INVALID missing PC_RESTIC_PASSWORD_FILE / PC_RESTIC_PASSWORD"
  }
  Register-Secret $pcPassword

  # Defer OCI source password file read/ACL check until Assert-ValidSourceConfig (AFTER local pre-expiry)
  # so missing/invalid source credentials never block local 14-day retention or pollute pre-stage logs.
  $ociRepo = if ($kv.ContainsKey("OCI_RESTIC_REPOSITORY")) { $kv["OCI_RESTIC_REPOSITORY"].Trim() } else { "" }
  $ociPwFile = if ($kv.ContainsKey("OCI_RESTIC_PASSWORD_FILE")) { $kv["OCI_RESTIC_PASSWORD_FILE"].Trim() } else { "" }
  $ociPwInline = if ($kv.ContainsKey("OCI_RESTIC_PASSWORD")) { $kv["OCI_RESTIC_PASSWORD"] } else { "" }
  $ociPassword = ""
  if ([string]::IsNullOrEmpty($ociPwFile) -and -not [string]::IsNullOrEmpty($ociPwInline)) {
    $ociPassword = $ociPwInline
    Register-Secret $ociPassword
  }

  $lockFile = if ($kv.ContainsKey("PC_LOCK_FILE") -and -not [string]::IsNullOrWhiteSpace($kv["PC_LOCK_FILE"])) {
    Normalize-LocalPath $kv["PC_LOCK_FILE"]
  } else {
    Join-Path $baseDir "runtime\pc-pull.lock"
  }
  $cacheDir = if ($kv.ContainsKey("PC_CACHE_DIR") -and -not [string]::IsNullOrWhiteSpace($kv["PC_CACHE_DIR"])) {
    Normalize-LocalPath $kv["PC_CACHE_DIR"]
  } else {
    Join-Path $baseDir "cache"
  }
  $logDir = if ($kv.ContainsKey("PC_LOG_DIR") -and -not [string]::IsNullOrWhiteSpace($kv["PC_LOG_DIR"])) {
    Normalize-LocalPath $kv["PC_LOG_DIR"]
  } else {
    Join-Path $baseDir "logs"
  }
  $script:LogFilePath = Join-Path $logDir "pc-pull.log"

  $resticBin = if ($kv.ContainsKey("RESTIC_BIN") -and -not [string]::IsNullOrWhiteSpace($kv["RESTIC_BIN"])) {
    [Environment]::ExpandEnvironmentVariables($kv["RESTIC_BIN"].Trim())
  } else {
    "restic"
  }
  $pythonBin = if ($kv.ContainsKey("PYTHON_BIN") -and -not [string]::IsNullOrWhiteSpace($kv["PYTHON_BIN"])) {
    [Environment]::ExpandEnvironmentVariables($kv["PYTHON_BIN"].Trim())
  } else {
    "python"
  }

  $expireSelect = ""
  if ($kv.ContainsKey("EXPIRE_SELECT_PATH") -and -not [string]::IsNullOrWhiteSpace($kv["EXPIRE_SELECT_PATH"])) {
    $expireSelect = Normalize-LocalPath $kv["EXPIRE_SELECT_PATH"]
  } else {
    $sameDirCandidate = Join-Path $SCRIPT_DIR "expire-select.py"
    $parentDirCandidate = Join-Path (Split-Path -Parent $SCRIPT_DIR) "expire-select.py"
    if (Test-Path -LiteralPath $sameDirCandidate) {
      $expireSelect = $sameDirCandidate
    } elseif (Test-Path -LiteralPath $parentDirCandidate) {
      $expireSelect = $parentDirCandidate
    } else {
      Fail-Run "CONFIG_INVALID expire-select.py not found beside pc-pull.ps1 or in parent directory"
    }
  }
  Assert-RegularFileNoSymlink $expireSelect "expire-select.py"

  $copyMaxRetries = 1
  if ($kv.ContainsKey("COPY_MAX_RETRIES") -and -not [string]::IsNullOrWhiteSpace($kv["COPY_MAX_RETRIES"])) {
    if ($kv["COPY_MAX_RETRIES"].Trim() -notmatch '^[0-3]$') {
      Fail-Run "CONFIG_INVALID COPY_MAX_RETRIES must be an integer 0..3"
    }
    $copyMaxRetries = [int]$kv["COPY_MAX_RETRIES"].Trim()
  }
  $copyRetryDelay = 15
  if ($kv.ContainsKey("COPY_RETRY_DELAY_SECONDS") -and -not [string]::IsNullOrWhiteSpace($kv["COPY_RETRY_DELAY_SECONDS"])) {
    if ($kv["COPY_RETRY_DELAY_SECONDS"].Trim() -notmatch '^[0-9]+$' -or [int]$kv["COPY_RETRY_DELAY_SECONDS"].Trim() -gt 300) {
      Fail-Run "CONFIG_INVALID COPY_RETRY_DELAY_SECONDS must be an integer 0..300"
    }
    $copyRetryDelay = [int]$kv["COPY_RETRY_DELAY_SECONDS"].Trim()
  }

  $nowOverride = $null
  if ($kv.ContainsKey("PC_NOW_EPOCH_OVERRIDE") -and -not [string]::IsNullOrWhiteSpace($kv["PC_NOW_EPOCH_OVERRIDE"])) {
    if ($kv["PC_NOW_EPOCH_OVERRIDE"].Trim() -notmatch '^[1-9][0-9]*$') {
      Fail-Run "CONFIG_INVALID PC_NOW_EPOCH_OVERRIDE must be a positive integer epoch"
    }
    $nowOverride = [long]$kv["PC_NOW_EPOCH_OVERRIDE"].Trim()
  }
  $postNowOverride = $null
  if ($kv.ContainsKey("PC_POST_NOW_EPOCH_OVERRIDE") -and -not [string]::IsNullOrWhiteSpace($kv["PC_POST_NOW_EPOCH_OVERRIDE"])) {
    if ($kv["PC_POST_NOW_EPOCH_OVERRIDE"].Trim() -notmatch '^[1-9][0-9]*$') {
      Fail-Run "CONFIG_INVALID PC_POST_NOW_EPOCH_OVERRIDE must be a positive integer epoch"
    }
    $postNowOverride = [long]$kv["PC_POST_NOW_EPOCH_OVERRIDE"].Trim()
  }

  return [pscustomobject]@{
    EnvFile               = $resolvedEnv
    CheckAcl              = $checkAcl
    PcRepo                = $pcRepo
    PcPassword            = $pcPassword
    OciRepo               = $ociRepo
    OciPasswordFile       = $ociPwFile
    OciPasswordInline     = $ociPwInline
    OciPassword           = $ociPassword
    OciAccessKey          = if ($kv.ContainsKey("OCI_AWS_ACCESS_KEY_ID")) { $kv["OCI_AWS_ACCESS_KEY_ID"].Trim() } else { "" }
    OciSecretKey          = if ($kv.ContainsKey("OCI_AWS_SECRET_ACCESS_KEY")) { $kv["OCI_AWS_SECRET_ACCESS_KEY"].Trim() } else { "" }
    OciRegion             = if ($kv.ContainsKey("OCI_AWS_DEFAULT_REGION")) { $kv["OCI_AWS_DEFAULT_REGION"].Trim() } else { "" }
    LockFile              = $lockFile
    CacheDir              = $cacheDir
    LogDir                = $logDir
    ResticBin             = $resticBin
    PythonBin             = $pythonBin
    ExpireSelectPath      = $expireSelect
    CopyMaxRetries        = $copyMaxRetries
    CopyRetryDelaySeconds = $copyRetryDelay
    AllowCustomPaths      = $allowCustomPaths
    AllowLocalSource      = $allowLocalSource
    NowOverride           = $nowOverride
    PostNowOverride       = $postNowOverride
  }
}

function Acquire-PcLock {
  param([string]$LockFile)
  $lockDir = Split-Path -Parent $LockFile
  if (-not (Test-Path -LiteralPath $lockDir)) {
    New-Item -ItemType Directory -Path $lockDir -Force | Out-Null
  }
  if (Test-IsReparsePoint $LockFile) {
    Fail-Run "LOCK_FILE_SYMLINK refusing symlink/reparse point at $LockFile"
  }
  try {
    $script:LockStream = [System.IO.File]::Open(
      $LockFile,
      [System.IO.FileMode]::OpenOrCreate,
      [System.IO.FileAccess]::ReadWrite,
      [System.IO.FileShare]::None
    )
  } catch [System.IO.IOException] {
    Fail-Run "LOCK_HELD lock_file=$LockFile" 20
  }
}

function Release-PcLock {
  if ($null -ne $script:LockStream) {
    try { $script:LockStream.Close() } catch {}
    try { $script:LockStream.Dispose() } catch {}
    $script:LockStream = $null
  }
}

function Ensure-Directory {
  param([string]$DirPath)
  if (-not (Test-Path -LiteralPath $DirPath)) {
    New-Item -ItemType Directory -Path $DirPath -Force | Out-Null
  }
}

function Invoke-LocalRestic {
  param(
    [pscustomobject]$Cfg,
    [string[]]$ResticArgs
  )
  Ensure-Directory $Cfg.CacheDir
  # Strictly strip all OCI/S3/secondary-repo vars so local forget/prune can NEVER target OCI.
  $remove = @(
    "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_DEFAULT_REGION", "AWS_SESSION_TOKEN",
    "AWS_PROFILE", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE",
    "RESTIC_FROM_REPOSITORY", "RESTIC_FROM_PASSWORD", "RESTIC_FROM_PASSWORD_FILE",
    "RESTIC_FROM_REPOSITORY_FILE", "RESTIC_FROM_KEY_HINT", "RESTIC_FROM_PASSWORD_COMMAND",
    "RESTIC_REPOSITORY2", "RESTIC_PASSWORD2", "RESTIC_PASSWORD_FILE2", "RESTIC_REPOSITORY_FILE2",
    "RESTIC_PASSWORD_FILE", "RESTIC_PASSWORD_COMMAND", "RESTIC_REPOSITORY_FILE", "RESTIC_KEY_HINT"
  )
  $envMap = @{
    "RESTIC_REPOSITORY" = $Cfg.PcRepo
    "RESTIC_PASSWORD"   = $Cfg.PcPassword
    "RESTIC_CACHE_DIR"  = $Cfg.CacheDir
  }
  $fullArgs = @("--cache-dir", $Cfg.CacheDir, "-r", $Cfg.PcRepo) + $ResticArgs
  return Invoke-ExternalProcess -FilePath $Cfg.ResticBin -Arguments $fullArgs -SetEnv $envMap -RemoveEnv $remove
}

function Invoke-SourceResticReadOnly {
  param(
    [pscustomobject]$Cfg,
    [string[]]$ResticArgs
  )
  Ensure-Directory $Cfg.CacheDir
  $remove = @(
    "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_DEFAULT_REGION", "AWS_SESSION_TOKEN",
    "AWS_PROFILE", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE",
    "RESTIC_FROM_REPOSITORY", "RESTIC_FROM_PASSWORD", "RESTIC_FROM_PASSWORD_FILE",
    "RESTIC_FROM_REPOSITORY_FILE", "RESTIC_FROM_KEY_HINT", "RESTIC_FROM_PASSWORD_COMMAND",
    "RESTIC_REPOSITORY2", "RESTIC_PASSWORD2", "RESTIC_PASSWORD_FILE2", "RESTIC_REPOSITORY_FILE2",
    "RESTIC_PASSWORD_FILE", "RESTIC_PASSWORD_COMMAND", "RESTIC_REPOSITORY_FILE", "RESTIC_KEY_HINT"
  )
  $envMap = @{
    "RESTIC_REPOSITORY" = $Cfg.OciRepo
    "RESTIC_PASSWORD"   = $Cfg.OciPassword
    "RESTIC_CACHE_DIR"  = $Cfg.CacheDir
  }
  if (-not [string]::IsNullOrEmpty($Cfg.OciAccessKey)) { $envMap["AWS_ACCESS_KEY_ID"] = $Cfg.OciAccessKey }
  if (-not [string]::IsNullOrEmpty($Cfg.OciSecretKey)) { $envMap["AWS_SECRET_ACCESS_KEY"] = $Cfg.OciSecretKey }
  if (-not [string]::IsNullOrEmpty($Cfg.OciRegion)) { $envMap["AWS_DEFAULT_REGION"] = $Cfg.OciRegion }

  # Always --no-lock on read-only source repository.
  $fullArgs = @("--no-lock", "--cache-dir", $Cfg.CacheDir) + $ResticArgs
  return Invoke-ExternalProcess -FilePath $Cfg.ResticBin -Arguments $fullArgs -SetEnv $envMap -RemoveEnv $remove
}

function Invoke-CopyOrFromInitRestic {
  param(
    [pscustomobject]$Cfg,
    [string[]]$ResticArgs
  )
  Ensure-Directory $Cfg.CacheDir
  $remove = @(
    "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_DEFAULT_REGION", "AWS_SESSION_TOKEN",
    "AWS_PROFILE", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE",
    "RESTIC_REPOSITORY2", "RESTIC_PASSWORD2", "RESTIC_PASSWORD_FILE2", "RESTIC_REPOSITORY_FILE2",
    "RESTIC_PASSWORD_FILE", "RESTIC_PASSWORD_COMMAND", "RESTIC_REPOSITORY_FILE", "RESTIC_KEY_HINT",
    "RESTIC_FROM_PASSWORD_FILE", "RESTIC_FROM_PASSWORD_COMMAND", "RESTIC_FROM_REPOSITORY_FILE", "RESTIC_FROM_KEY_HINT"
  )
  # Direction: destination = local PC (-r <LOCAL_DST>, append-locked by restic),
  #            source      = read-only OCI (--from-repo <OCI_SRC>, --no-lock applies to source only in restic 0.19.1).
  $envMap = @{
    "RESTIC_REPOSITORY"      = $Cfg.PcRepo
    "RESTIC_PASSWORD"        = $Cfg.PcPassword
    "RESTIC_FROM_REPOSITORY" = $Cfg.OciRepo
    "RESTIC_FROM_PASSWORD"   = $Cfg.OciPassword
    "RESTIC_CACHE_DIR"       = $Cfg.CacheDir
  }
  if (-not [string]::IsNullOrEmpty($Cfg.OciAccessKey)) { $envMap["AWS_ACCESS_KEY_ID"] = $Cfg.OciAccessKey }
  if (-not [string]::IsNullOrEmpty($Cfg.OciSecretKey)) { $envMap["AWS_SECRET_ACCESS_KEY"] = $Cfg.OciSecretKey }
  if (-not [string]::IsNullOrEmpty($Cfg.OciRegion)) { $envMap["AWS_DEFAULT_REGION"] = $Cfg.OciRegion }

  $fullArgs = @("--no-lock", "--cache-dir", $Cfg.CacheDir, "-r", $Cfg.PcRepo) + $ResticArgs
  return Invoke-ExternalProcess -FilePath $Cfg.ResticBin -Arguments $fullArgs -SetEnv $envMap -RemoveEnv $remove
}

function Invoke-ExpireSelector {
  param(
    [pscustomobject]$Cfg,
    [string]$SnapshotsJson,
    [long]$NowEpoch,
    [ValidateSet("expired", "kept")][string]$Mode = "expired"
  )
  $args = @("-I", $Cfg.ExpireSelectPath, "--mode", $Mode, [string]$NowEpoch, [string]$RETENTION_SECONDS)
  $res = Invoke-ExternalProcess -FilePath $Cfg.PythonBin -Arguments $args -StdinText $SnapshotsJson
  if ($res.ExitCode -ne 0) {
    Write-RedactedOutput $res.StdErr -IsError
    return [pscustomobject]@{
      Ok      = $false
      Summary = ""
      Ids     = @()
      Error   = (Redact-Text ($res.StdErr.Trim()))
    }
  }
  $lines = @($res.StdOut -split "`r?`n" | Where-Object { $_.Length -gt 0 })
  if (@($lines).Count -lt 1) {
    return [pscustomobject]@{
      Ok      = $false
      Summary = ""
      Ids     = @()
      Error   = "empty output from expire-select.py"
    }
  }
  $summary = $lines[0]
  $ids = @()
  for ($i = 1; $i -lt @($lines).Count; $i++) {
    $parts = @($lines[$i] -split '\s+')
    if (@($parts).Count -ge 1 -and $parts[0] -match '^[0-9a-f]{64}$') {
      $ids += $parts[0]
    } else {
      return [pscustomobject]@{
        Ok      = $false
        Summary = ""
        Ids     = @()
        Error   = "unexpected selector output line"
      }
    }
  }
  return [pscustomobject]@{
    Ok      = $true
    Summary = $summary
    Ids     = @($ids)
    Error   = ""
  }
}

function Assert-ValidSourceConfig {
  param([pscustomobject]$Cfg)
  if ([string]::IsNullOrWhiteSpace($Cfg.OciRepo)) {
    Fail-Run "SOURCE_CONFIG_INVALID missing OCI_RESTIC_REPOSITORY"
  }
  if (-not [string]::IsNullOrEmpty($Cfg.OciPasswordFile) -and -not [string]::IsNullOrEmpty($Cfg.OciPasswordInline)) {
    Fail-Run "SOURCE_CONFIG_INVALID set either OCI_RESTIC_PASSWORD_FILE or OCI_RESTIC_PASSWORD, not both"
  }
  if ([string]::IsNullOrEmpty($Cfg.OciPassword) -and -not [string]::IsNullOrEmpty($Cfg.OciPasswordFile)) {
    $Cfg.OciPassword = Read-PasswordFile $Cfg.OciPasswordFile "OCI_RESTIC_PASSWORD_FILE" $Cfg.CheckAcl
    Register-Secret $Cfg.OciPassword
  }
  if ([string]::IsNullOrEmpty($Cfg.OciPassword)) {
    Fail-Run "SOURCE_CONFIG_INVALID missing OCI_RESTIC_PASSWORD_FILE / OCI_RESTIC_PASSWORD"
  }
  if ($Cfg.PcPassword -ceq $Cfg.OciPassword) {
    Fail-Run "SOURCE_CONFIG_INVALID PC_RESTIC_PASSWORD must be separate from OCI_RESTIC_PASSWORD"
  }
  if ($Cfg.OciRepo -match '^s3:https?://[^/\s]+@') {
    Fail-Run "SOURCE_CONFIG_INVALID OCI_RESTIC_REPOSITORY must not embed credentials in URL"
  }
  if (-not $Cfg.AllowLocalSource) {
    if ($Cfg.OciRepo -notmatch '^s3:https://') {
      Fail-Run "SOURCE_CONFIG_INVALID OCI_RESTIC_REPOSITORY must use s3:https:// (refusing non-S3 source)"
    }
  } else {
    if (-not (Test-HasRemoteBackendScheme $Cfg.OciRepo)) {
      $normSrc = Normalize-LocalPath $Cfg.OciRepo
      if ($normSrc.Equals($Cfg.PcRepo, [System.StringComparison]::OrdinalIgnoreCase)) {
        Fail-Run "SOURCE_EQUALS_DEST source and local destination must be distinct repositories"
      }
    }
  }
  if ($Cfg.OciRepo.StartsWith("s3:", [System.StringComparison]::OrdinalIgnoreCase)) {
    if ([string]::IsNullOrWhiteSpace($Cfg.OciAccessKey) -or [string]::IsNullOrWhiteSpace($Cfg.OciSecretKey)) {
      Fail-Run "SOURCE_CONFIG_INVALID missing OCI_AWS_ACCESS_KEY_ID or OCI_AWS_SECRET_ACCESS_KEY for S3 source"
    }
  }
}

function Assert-LocalRepoReady {
  param([pscustomobject]$Cfg)
  $res = Invoke-LocalRestic -Cfg $Cfg -ResticArgs @("cat", "config")
  if ($res.ExitCode -ne 0) {
    Write-RedactedOutput $res.StdErr -IsError
    if ($res.ExitCode -eq 12) {
      Fail-Run "LOCAL_REPO_AUTH_FAIL local PC repository password rejected; local retention not performed"
    }
    if ($res.ExitCode -eq 11) {
      Fail-Run "LOCAL_REPO_LOCKED local PC repository is locked; local retention not performed"
    }
    Fail-Run "LOCAL_REPO_UNAVAILABLE local PC repository check failed (run 'pc-pull.ps1 init' or verify local path/permissions)"
  }
}

function Invoke-LocalRetention {
  param(
    [pscustomobject]$Cfg,
    [string]$Phase,
    [long]$NowEpoch
  )
  $tag = $Phase.ToUpperInvariant()

  # 1. List local snapshots
  $snapRes = Invoke-LocalRestic -Cfg $Cfg -ResticArgs @("snapshots", "--tag", "gongchae-bell", "--json")
  if ($snapRes.ExitCode -ne 0) {
    Write-RedactedOutput $snapRes.StdErr -IsError
    Fail-Run "${tag}_RETENTION_SNAPSHOTS_FAIL unable to list local snapshots; retention state unverified"
  }

  # 2. Select expired >14d via shared expire-select.py
  $sel = Invoke-ExpireSelector -Cfg $Cfg -SnapshotsJson $snapRes.StdOut -NowEpoch $NowEpoch -Mode "expired"
  if (-not $sel.Ok) {
    Fail-Run "${tag}_RETENTION_SELECT_FAIL local snapshot list rejected; retention state unverified"
  }
  Write-Log ("{0}_RETENTION_SELECT {1}" -f $tag, $sel.Summary)

  # 3. Forget explicit full 64-hex IDs only (no latest-copy exception; 0 copies allowed)
  $selIds = @($sel.Ids)
  $forgotten = $selIds.Count
  if ($forgotten -gt 0) {
    Write-Log ("{0}_RETENTION_FORGET forgetting {1} local snapshot(s) older than 14 days: {2}" -f $tag, $forgotten, ($selIds -join " "))
    $forgetArgs = @("forget") + $selIds
    $forgetRes = Invoke-LocalRestic -Cfg $Cfg -ResticArgs $forgetArgs
    Write-RedactedOutput $forgetRes.StdOut
    if ($forgetRes.ExitCode -ne 0) {
      Write-RedactedOutput $forgetRes.StdErr -IsError
      Fail-Run "${tag}_RETENTION_FORGET_FAIL expired=$forgotten local forget did not complete; retention not met"
    }
  }

  # 4. Always prune --max-unused 0 so forgotten data and orphaned packs from earlier failures are physically removed
  $pruneRes = Invoke-LocalRestic -Cfg $Cfg -ResticArgs @("prune", "--max-unused", "0")
  Write-RedactedOutput $pruneRes.StdOut
  if ($pruneRes.ExitCode -ne 0) {
    Write-RedactedOutput $pruneRes.StdErr -IsError
    Fail-Run "${tag}_RETENTION_PRUNE_FAIL forgotten=$forgotten local prune failed; physical deletion delayed and retention not met"
  }

  # 5. Re-list and verify 0 expired remain at the same cutoff
  $verifySnap = Invoke-LocalRestic -Cfg $Cfg -ResticArgs @("snapshots", "--tag", "gongchae-bell", "--json")
  if ($verifySnap.ExitCode -ne 0) {
    Write-RedactedOutput $verifySnap.StdErr -IsError
    Fail-Run "${tag}_RETENTION_VERIFY_SNAPSHOTS_FAIL unable to re-list local snapshots after prune; retention state unverified"
  }
  $verifySel = Invoke-ExpireSelector -Cfg $Cfg -SnapshotsJson $verifySnap.StdOut -NowEpoch $NowEpoch -Mode "expired"
  if (-not $verifySel.Ok) {
    Fail-Run "${tag}_RETENTION_VERIFY_SELECT_FAIL re-listed local snapshots rejected; retention state unverified"
  }
  if (@($verifySel.Ids).Count -gt 0) {
    Fail-Run "${tag}_RETENTION_VERIFY_FAIL $(@($verifySel.Ids).Count) expired snapshot(s) still present after local forget/prune"
  }

  $summaryLine = "{0}_RETENTION_SUMMARY policy=wall-clock-14d forgotten={1} {2}" -f $tag, $forgotten, $verifySel.Summary
  Write-Log $summaryLine
  return [pscustomobject]@{
    Forgotten = $forgotten
    Summary   = $verifySel.Summary
    Line      = $summaryLine
  }
}

function Invoke-CmdInit {
  param([pscustomobject]$Cfg)
  Ensure-Directory $Cfg.PcRepo
  $check = Invoke-LocalRestic -Cfg $Cfg -ResticArgs @("cat", "config")
  if ($check.ExitCode -eq 0) {
    Write-Log "PC_RESTIC_REPO_ALREADY_INITIALIZED repo=$($Cfg.PcRepo)"
    return
  }
  if ($check.ExitCode -eq 12) {
    Write-RedactedOutput $check.StdErr -IsError
    Fail-Run "LOCAL_REPO_AUTH_FAIL existing local repo rejected password during init"
  }

  if (-not $StandaloneInit -and -not [string]::IsNullOrWhiteSpace($Cfg.OciRepo)) {
    Assert-ValidSourceConfig $Cfg
    Write-Log "Initializing local PC repository with chunker parameters copied from read-only source (--no-lock)"
    $initRes = Invoke-CopyOrFromInitRestic -Cfg $Cfg -ResticArgs @("init", "--from-repo", $Cfg.OciRepo, "--copy-chunker-params")
    Write-RedactedOutput $initRes.StdOut
    if ($initRes.ExitCode -ne 0) {
      Write-RedactedOutput $initRes.StdErr -IsError
      Fail-Run "PC_RESTIC_INIT_FAIL unable to initialize local repository from source"
    }
    Write-Log "PC_RESTIC_REPO_INITIALIZED mode=copy-chunker-params"
    return
  }

  Write-Log "Initializing standalone local PC repository"
  $initRes = Invoke-LocalRestic -Cfg $Cfg -ResticArgs @("init")
  Write-RedactedOutput $initRes.StdOut
  if ($initRes.ExitCode -ne 0) {
    Write-RedactedOutput $initRes.StdErr -IsError
    Fail-Run "PC_RESTIC_INIT_FAIL unable to initialize standalone local repository"
  }
  Write-Log "PC_RESTIC_REPO_INITIALIZED mode=standalone"
}

function Invoke-CmdSnapshots {
  param([pscustomobject]$Cfg)
  Assert-LocalRepoReady $Cfg
  $res = Invoke-LocalRestic -Cfg $Cfg -ResticArgs @("snapshots", "--tag", "gongchae-bell", "--compact")
  Write-RedactedOutput $res.StdOut
  if ($res.ExitCode -ne 0) {
    Write-RedactedOutput $res.StdErr -IsError
    Fail-Run "LOCAL_RESTIC_SNAPSHOTS_FAIL"
  }
}

function Invoke-CmdRun {
  param([pscustomobject]$Cfg)
  $script:InRun = $true

  $preNowEpoch = if ($null -ne $Cfg.NowOverride) { $Cfg.NowOverride } else { Get-UtcEpochNow }

  # Step 1: Local repo readiness & PRE-EXPIRY BEFORE ANY NETWORK/OCI CALL (ADR-0017 / user decision 04).
  Assert-LocalRepoReady $Cfg
  $pre = Invoke-LocalRetention -Cfg $Cfg -Phase "pre" -NowEpoch $preNowEpoch

  # Step 2: Validate source config & read-only source snapshot list, then copy ONLY valid <=14d full IDs.
  $sourceFailed = $false
  $sourceFailReason = ""
  $copiedCandidates = 0

  try {
    Assert-ValidSourceConfig $Cfg

    $attempt = 0
    $maxAttempts = 1 + $Cfg.CopyMaxRetries
    while ($attempt -lt $maxAttempts) {
      $attempt++
      $attemptEpoch = if ($null -ne $Cfg.NowOverride) { $Cfg.NowOverride } else { Get-UtcEpochNow }

      $srcSnap = Invoke-SourceResticReadOnly -Cfg $Cfg -ResticArgs @("snapshots", "--tag", "gongchae-bell", "--json")
      if ($srcSnap.ExitCode -ne 0) {
        Write-RedactedOutput $srcSnap.StdErr -IsError
        $sourceFailed = $true
        $sourceFailReason = "SOURCE_SNAPSHOTS_FAIL unable to list read-only source snapshots (attempt $attempt/$maxAttempts, rc=$($srcSnap.ExitCode))"
        if ($attempt -lt $maxAttempts) {
          Write-Log "WARN: $sourceFailReason; retrying after $($Cfg.CopyRetryDelaySeconds)s" -IsError
          if ($Cfg.CopyRetryDelaySeconds -gt 0) {
            Start-Sleep -Seconds $Cfg.CopyRetryDelaySeconds
          }
          continue
        }
        break
      }

      $srcSel = Invoke-ExpireSelector -Cfg $Cfg -SnapshotsJson $srcSnap.StdOut -NowEpoch $attemptEpoch -Mode "kept"
      if (-not $srcSel.Ok) {
        $sourceFailed = $true
        $sourceFailReason = "SOURCE_SELECT_FAIL read-only source snapshot list rejected ($($srcSel.Error))"
        break
      }
      Write-Log ("SOURCE_SELECT attempt={0}/{1} {2}" -f $attempt, $maxAttempts, $srcSel.Summary)

      $srcIds = @($srcSel.Ids)
      if ($srcIds.Count -eq 0) {
        Write-Log "SOURCE_COPY_SKIPPED valid_candidates=0 (source empty or all snapshots older than 14 days)"
        $copiedCandidates = 0
        $sourceFailed = $false
        $sourceFailReason = ""
        break
      }

      Write-Log ("SOURCE_COPY_START attempt={0}/{1} candidates={2} ids={3}" -f $attempt, $maxAttempts, $srcIds.Count, ($srcIds -join " "))
      $copyArgs = @("copy", "--from-repo", $Cfg.OciRepo) + $srcIds
      $copyRes = Invoke-CopyOrFromInitRestic -Cfg $Cfg -ResticArgs $copyArgs
      Write-RedactedOutput $copyRes.StdOut
      if ($copyRes.ExitCode -eq 0) {
        $copiedCandidates = $srcIds.Count
        $sourceFailed = $false
        $sourceFailReason = ""
        Write-Log "SOURCE_COPY_OK candidates=$copiedCandidates"
        break
      }

      Write-RedactedOutput $copyRes.StdErr -IsError
      if ($copyRes.ExitCode -eq 11) {
        $sourceFailed = $true
        $sourceFailReason = "LOCAL_DEST_LOCKED destination repository lock conflict during copy (rc=11)"
        break
      }

      $sourceFailed = $true
      $sourceFailReason = "SOURCE_COPY_FAIL restic copy exited rc=$($copyRes.ExitCode) (attempt $attempt/$maxAttempts; possible network or source prune race)"
      if ($attempt -lt $maxAttempts) {
        Write-Log "WARN: $sourceFailReason; retrying after $($Cfg.CopyRetryDelaySeconds)s" -IsError
        if ($Cfg.CopyRetryDelaySeconds -gt 0) {
          Start-Sleep -Seconds $Cfg.CopyRetryDelaySeconds
        }
      }
    }
  } catch {
    $sourceFailed = $true
    $sourceFailReason = Redact-Text $_.Exception.Message
  }

  # Step 3: ALWAYS run post-copy local retention & prune --max-unused 0 at post-transfer UTC epoch
  # (cleans boundary-crossed snapshots AND any orphaned packs from a failed/partial copy).
  if ($sourceFailed) {
    Write-Log "WARN: source stage failed ($sourceFailReason); running post-copy local retention/prune before failing" -IsError
  }
  $postNowEpoch = if ($null -ne $Cfg.PostNowOverride) {
    $Cfg.PostNowOverride
  } elseif ($null -ne $Cfg.NowOverride) {
    $Cfg.NowOverride
  } else {
    Get-UtcEpochNow
  }
  $post = Invoke-LocalRetention -Cfg $Cfg -Phase "post" -NowEpoch $postNowEpoch

  if ($sourceFailed) {
    Write-Log "LOCAL_RETENTION_OK_BUT_SOURCE_FAILED pre_forgotten=$($pre.Forgotten) post_forgotten=$($post.Forgotten) $($post.Summary)" -IsError
    Fail-Run $sourceFailReason
  }

  $okSummary = "PC_PULL_OK copied_candidates=$copiedCandidates pre_forgotten=$($pre.Forgotten) post_forgotten=$($post.Forgotten) $($post.Summary)"
  if (-not (Send-Heartbeat "" $okSummary)) {
    Fail-Run "HEARTBEAT_PING_FAIL success heartbeat failed"
  }

  $script:RunSucceeded = $true
  Write-Log $okSummary
}

$exitCode = 0
try {
  $cfg = Load-Config -Path $EnvFile
  Acquire-PcLock -LockFile $cfg.LockFile

  switch ($Command) {
    "run"       { Invoke-CmdRun -Cfg $cfg }
    "init"      { Invoke-CmdInit -Cfg $cfg }
    "snapshots" { Invoke-CmdSnapshots -Cfg $cfg }
  }
} catch {
  $exitCode = 1
  if ($_.Exception.Data -and $_.Exception.Data.Contains("ExitCode")) {
    $exitCode = [int]$_.Exception.Data["ExitCode"]
  }
  if ([string]::IsNullOrEmpty($script:FailReason)) {
    $script:FailReason = Redact-Text $_.Exception.Message
    Write-Log ("ERROR: " + $script:FailReason) -IsError
  }
  if ($script:InRun -and -not $script:RunSucceeded) {
    $null = Send-Heartbeat "/fail" $script:FailReason
  }
} finally {
  Release-PcLock
}

exit $exitCode
