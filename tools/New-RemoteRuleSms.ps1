#Requires -Version 7.0

<#
.SYNOPSIS
Generates a remote-rule HMAC key or one authenticated MC SMS Forwarder command SMS.

.DESCRIPTION
Generate Key mode creates a random 256-bit unpadded Base64URL key. Build SMS mode accepts one
cleartext literal sender, sender RegEx, or message RegEx, encodes it as unpadded Base64URL, and
appends the HMAC-SHA256 tag in the same encoding. Output is always written to the terminal;
-CopyToClipboard and -OutputPath optionally duplicate it to the clipboard and/or a UTF-8 file.
Command mode also reports the final character count and estimated GSM-7 SMS segment count; a
multipart command produces a warning because it may cost more and is more vulnerable to partial
delivery.

.PARAMETER GenerateKey
Generates a new random 256-bit shared key instead of building a command.

.PARAMETER RuleType
Rule carried by the command: LiteralSender, SenderRegex, or MessageRegex.

.PARAMETER Value
Cleartext rule value to encode. Required when RuleType is supplied. Line breaks and values longer
than 4096 characters are rejected.

.PARAMETER HmacKey
Optional SecureString containing the canonical 43-character unpadded Base64URL shared key. When
omitted, the script prompts for it without echoing.

.PARAMETER CopyToClipboard
Copies the generated key or command to the local clipboard. Clipboard history and other
applications may retain it.

.PARAMETER OutputPath
Optional UTF-8 output file. The parent directory must already exist and must be outside this
repository.

.PARAMETER Force
Replaces an existing OutputPath file.

.OUTPUTS
System.String. The generated key or command is always written to the success output stream.
Command size information is written to the host, and multipart risk is written to the warning
stream, so automation still receives only the generated string on the success stream.

.EXAMPLE
pwsh .\tools\New-RemoteRuleSms.ps1 -GenerateKey -CopyToClipboard

.EXAMPLE
pwsh .\tools\New-RemoteRuleSms.ps1 -RuleType SenderRegex -Value '^chave.*digital$' -CopyToClipboard

.EXAMPLE
$key = Read-Host "Remote SMS HMAC key" -AsSecureString
& .\tools\New-RemoteRuleSms.ps1 -RuleType MessageRegex -Value 'otp\s+\d{6}' -HmacKey $key
#>

[CmdletBinding(DefaultParameterSetName = "GenerateKey", PositionalBinding = $false)]
param(
    [Parameter(Mandatory, ParameterSetName = "GenerateKey")]
    [switch] $GenerateKey,

    [Parameter(Mandatory, ParameterSetName = "BuildCommand")]
    [ValidateSet("LiteralSender", "SenderRegex", "MessageRegex")]
    [string] $RuleType,

    [Parameter(Mandatory, ParameterSetName = "BuildCommand")]
    [ValidateNotNull()]
    [string] $Value,

    [Parameter(ParameterSetName = "BuildCommand")]
    [Security.SecureString] $HmacKey,

    [Alias("Copy")]
    [switch] $CopyToClipboard,

    [string] $OutputPath,

    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$maximumValueLength = 4096

function Resolve-FullPath {
    param(
        [Parameter(Mandatory)]
        [string] $Path
    )

    $candidate = if ([IO.Path]::IsPathRooted($Path)) {
        $Path
    } else {
        Join-Path (Get-Location).Path $Path
    }
    return [IO.Path]::GetFullPath($candidate)
}

function Test-PathInsideRepository {
    param(
        [Parameter(Mandatory)]
        [string] $Path
    )

    $separator = [IO.Path]::DirectorySeparatorChar
    $rootPrefix = $repositoryRoot.TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    ) + $separator
    return $Path.Equals($repositoryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $Path.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)
}

function ConvertFrom-ProtectedValue {
    param(
        [Parameter(Mandatory)]
        [Security.SecureString] $ProtectedValue
    )

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($ProtectedValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Get-NormalizedHmacKey {
    $keyText = if ($null -eq $HmacKey) {
        ConvertFrom-ProtectedValue (Read-Host "Remote SMS HMAC key (hidden)" -AsSecureString)
    } else {
        ConvertFrom-ProtectedValue $HmacKey
    }
    $normalized = $keyText.Trim()
    $keyText = $null
    if ($normalized -notmatch '^[A-Za-z0-9_-]{43}$') {
        throw "HmacKey must be a canonical 43-character unpadded Base64URL key."
    }
    $decoded = ConvertFrom-Base64Url $normalized
    try {
        if ($decoded.Length -ne 32 -or (ConvertTo-Base64Url $decoded) -cne $normalized) {
            throw "HmacKey must be a canonical 43-character unpadded Base64URL key."
        }
    } finally {
        [Array]::Clear($decoded, 0, $decoded.Length)
    }
    return $normalized
}

function ConvertTo-Base64Url {
    param(
        [Parameter(Mandatory)]
        [byte[]] $Bytes
    )

    return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function ConvertFrom-Base64Url {
    param(
        [Parameter(Mandatory)]
        [string] $Value
    )

    if ($Value -notmatch '^[A-Za-z0-9_-]+$' -or $Value.Length % 4 -eq 1) {
        throw "Value is not canonical unpadded Base64URL."
    }
    $padded = $Value.Replace("-", "+").Replace("_", "/")
    switch ($padded.Length % 4) {
        2 { $padded += "==" }
        3 { $padded += "=" }
    }
    try {
        return [Convert]::FromBase64String($padded)
    } catch {
        throw "Value is not canonical unpadded Base64URL."
    }
}

function Write-GeneratedOutput {
    param(
        [Parameter(Mandatory)]
        [string] $GeneratedValue
    )

    $resolvedOutputPath = $null
    if (![string]::IsNullOrWhiteSpace($OutputPath)) {
        $resolvedOutputPath = Resolve-FullPath $OutputPath
        if (Test-PathInsideRepository $resolvedOutputPath) {
            throw "Write generated remote SMS values outside the repository."
        }
        $outputDirectory = [IO.Path]::GetDirectoryName($resolvedOutputPath)
        if (!(Test-Path -LiteralPath $outputDirectory -PathType Container)) {
            throw "Output directory does not exist: $outputDirectory"
        }
        if ((Test-Path -LiteralPath $resolvedOutputPath) -and !$Force) {
            throw "Output file already exists. Use -Force to replace it."
        }
    }

    Write-Output $GeneratedValue

    if ($CopyToClipboard) {
        Set-Clipboard -Value $GeneratedValue
        Write-Warning "Generated value copied to the local clipboard. Clipboard history and other applications may retain it."
    }

    if ($null -ne $resolvedOutputPath) {
        [IO.File]::WriteAllText(
            $resolvedOutputPath,
            $GeneratedValue,
            [Text.UTF8Encoding]::new($false)
        )
    }
}

function Write-SmsCommandSize {
    param(
        [Parameter(Mandatory)]
        [string] $Command
    )

    # Tokens, delimiters, and Base64URL output are all single-septet GSM-7 characters.
    $characterCount = $Command.Length
    $segmentCount = if ($characterCount -le 160) {
        1
    } else {
        [int] [Math]::Ceiling($characterCount / 153.0)
    }
    Write-Host "SMS command size: $characterCount GSM-7 characters; estimated segments: $segmentCount."
    if ($segmentCount -gt 1) {
        Write-Warning "This is a multipart SMS command ($segmentCount estimated segments). It may cost more and is more vulnerable to partial delivery."
    }
}

if ($PSCmdlet.ParameterSetName -eq "GenerateKey") {
    $keyBytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($keyBytes)
        Write-GeneratedOutput (ConvertTo-Base64Url $keyBytes)
    } finally {
        $rng.Dispose()
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    }
    return
}

if ([string]::IsNullOrWhiteSpace($Value)) {
    throw "Value cannot be blank."
}
if ($Value.Contains("`r") -or $Value.Contains("`n")) {
    throw "Value cannot contain a line break."
}
if ($Value.Length -gt $maximumValueLength) {
    throw "Value cannot contain more than $maximumValueLength characters."
}

$token = switch ($RuleType) {
    "LiteralSender" { "MCSMSSL" }
    "SenderRegex" { "MCSMSSR" }
    "MessageRegex" { "MCSMSMR" }
    default { throw "Unsupported command operation." }
}

$keyText = Get-NormalizedHmacKey
$keyBytes = ConvertFrom-Base64Url $keyText
$valueBytes = [Text.Encoding]::UTF8.GetBytes($Value)
$payload = [Convert]::ToBase64String($valueBytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
$authenticatedText = "$token`:$payload"
$authenticatedBytes = [Text.Encoding]::UTF8.GetBytes($authenticatedText)
$hmac = [Security.Cryptography.HMACSHA256]::new($keyBytes)
try {
    $mac = ConvertTo-Base64Url ($hmac.ComputeHash($authenticatedBytes))
    $command = "$authenticatedText`:$mac"
    Write-GeneratedOutput $command
    Write-SmsCommandSize $command
} finally {
    $hmac.Dispose()
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    [Array]::Clear($valueBytes, 0, $valueBytes.Length)
    [Array]::Clear($authenticatedBytes, 0, $authenticatedBytes.Length)
    $keyText = $null
}
