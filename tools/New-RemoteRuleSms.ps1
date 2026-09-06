#Requires -Version 7.0

<#
.SYNOPSIS
Generates a remote-rule HMAC key or one authenticated MC SMS Forwarder command SMS.

.DESCRIPTION
Generate Key mode creates a random 256-bit lowercase hexadecimal key. Build SMS mode accepts one
cleartext literal sender, sender RegEx, or message RegEx, encodes it as unpadded Base64URL, and
appends a lowercase HMAC-SHA256 over TOKEN:PAYLOAD. Output is always written to the terminal;
-Copy and -OutputPath optionally duplicate it to the clipboard and/or a UTF-8 file.

.EXAMPLE
pwsh .\tools\New-RemoteRuleSms.ps1 -GenerateKey -Copy

.EXAMPLE
pwsh .\tools\New-RemoteRuleSms.ps1 -SenderRegex -Value '^chave.*digital$' -Copy
#>

[CmdletBinding(DefaultParameterSetName = "GenerateKey")]
param(
    [Parameter(Mandatory, ParameterSetName = "GenerateKey")]
    [switch] $GenerateKey,

    [Parameter(Mandatory, ParameterSetName = "LiteralSender")]
    [switch] $LiteralSender,

    [Parameter(Mandatory, ParameterSetName = "SenderRegex")]
    [switch] $SenderRegex,

    [Parameter(Mandatory, ParameterSetName = "MessageRegex")]
    [switch] $MessageRegex,

    [Parameter(Mandatory, ParameterSetName = "LiteralSender")]
    [Parameter(Mandatory, ParameterSetName = "SenderRegex")]
    [Parameter(Mandatory, ParameterSetName = "MessageRegex")]
    [ValidateNotNull()]
    [string] $Value,

    [Parameter(ParameterSetName = "LiteralSender")]
    [Parameter(ParameterSetName = "SenderRegex")]
    [Parameter(ParameterSetName = "MessageRegex")]
    [Security.SecureString] $HmacKey,

    [switch] $Copy,

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
    $normalized = $keyText.Trim().ToLowerInvariant()
    $keyText = $null
    if ($normalized -notmatch '^[0-9a-f]{64}$') {
        throw "HmacKey must contain exactly 64 hexadecimal characters."
    }
    return $normalized
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

    if ($Copy) {
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

if ($PSCmdlet.ParameterSetName -eq "GenerateKey") {
    $keyBytes = [byte[]]::new(32)
    try {
        [Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
        Write-GeneratedOutput ([Convert]::ToHexString($keyBytes).ToLowerInvariant())
    } finally {
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

$token = switch ($PSCmdlet.ParameterSetName) {
    "LiteralSender" { "MCSMSSL" }
    "SenderRegex" { "MCSMSSR" }
    "MessageRegex" { "MCSMSMR" }
    default { throw "Unsupported command operation." }
}

$keyText = Get-NormalizedHmacKey
$keyBytes = [Convert]::FromHexString($keyText)
$valueBytes = [Text.Encoding]::UTF8.GetBytes($Value)
$payload = [Convert]::ToBase64String($valueBytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
$authenticatedText = "$token`:$payload"
$authenticatedBytes = [Text.Encoding]::UTF8.GetBytes($authenticatedText)
$hmac = [Security.Cryptography.HMACSHA256]::new($keyBytes)
try {
    $mac = [Convert]::ToHexString(
        $hmac.ComputeHash($authenticatedBytes)
    ).ToLowerInvariant()
    Write-GeneratedOutput "$authenticatedText`:$mac"
} finally {
    $hmac.Dispose()
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    [Array]::Clear($valueBytes, 0, $valueBytes.Length)
    [Array]::Clear($authenticatedBytes, 0, $authenticatedBytes.Length)
    $keyText = $null
}
