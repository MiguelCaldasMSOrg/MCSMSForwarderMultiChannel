#Requires -Version 7.0

<#
.SYNOPSIS
Creates an encrypted MC SMS Forwarder provisioning bundle.

.DESCRIPTION
Collects runtime settings interactively, or validates them from a plaintext JSON file, then writes
a versioned PBKDF2-HMAC-SHA256/AES-256-GCM .mcsmsconfig bundle. Secret prompts are hidden, generated
bundles and plaintext inputs are refused inside the repository, and existing output files require
-Force.

.PARAMETER OutputPath
Destination .mcsmsconfig path. Its parent directory must already exist and be outside this
repository.

.PARAMETER ConfigurationPath
Optional plaintext JSON configuration. When omitted, the script prompts for every included
setting. Keep plaintext configuration outside Git and remove it when it is no longer needed.

.PARAMETER Passphrase
Optional SecureString used to encrypt the bundle. When omitted, the script prompts twice without
echoing the passphrase.

.PARAMETER Force
Replaces an existing output bundle.

.EXAMPLE
pwsh .\tools\New-ProvisioningBundle.ps1 `
    -OutputPath "$HOME\Downloads\mc-sms-forwarder.mcsmsconfig"
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string] $OutputPath,

    [string] $ConfigurationPath,

    [Security.SecureString] $Passphrase,

    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$formatName = "mc-sms-forwarder-config"
$formatVersion = 1
$kdfAlgorithm = "PBKDF2-HMAC-SHA256"
$cipherAlgorithm = "AES-256-GCM"
$kdfIterations = 600000
$saltLength = 16
$nonceLength = 12
$tagLength = 16
$keyLength = 32
$minimumPassphraseLength = 12
$maximumPassphraseLength = 1024
$maximumListEntries = 1000
$maximumBundleBytes = 128 * 1024
$associatedData = [Text.Encoding]::UTF8.GetBytes("$formatName`:v$formatVersion")
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))

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
        [Security.SecureString] $Value
    )

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Read-RequiredValue {
    param(
        [Parameter(Mandatory)]
        [string] $Prompt,

        [Parameter(Mandatory)]
        [int] $MaximumLength
    )

    while ($true) {
        $value = (Read-Host $Prompt).Trim()
        if ($value.Length -eq 0) {
            Write-Warning "A value is required."
            continue
        }
        if ($value.Length -gt $MaximumLength) {
            Write-Warning "The value cannot exceed $MaximumLength characters."
            continue
        }
        return $value
    }
}

function Read-RequiredSecret {
    param(
        [Parameter(Mandatory)]
        [string] $Prompt,

        [Parameter(Mandatory)]
        [int] $MaximumLength
    )

    while ($true) {
        $secureValue = Read-Host $Prompt -AsSecureString
        $value = ConvertFrom-ProtectedValue $secureValue
        if ($value.Trim().Length -eq 0) {
            Write-Warning "A value is required."
            continue
        }
        if ($value.Trim().Length -gt $MaximumLength) {
            Write-Warning "The value cannot exceed $MaximumLength characters."
            continue
        }
        return $value.Trim()
    }
}

function Read-BooleanChoice {
    param(
        [Parameter(Mandatory)]
        [string] $Prompt,

        [Parameter(Mandatory)]
        [bool] $Default
    )

    $suffix = if ($Default) { "[Y/n]" } else { "[y/N]" }
    while ($true) {
        $answer = (Read-Host "$Prompt $suffix").Trim()
        if ($answer.Length -eq 0) {
            return $Default
        }
        switch ($answer.ToLowerInvariant()) {
            "y" { return $true }
            "yes" { return $true }
            "n" { return $false }
            "no" { return $false }
            default { Write-Warning "Enter yes or no." }
        }
    }
}

function Get-PropertyValue {
    param(
        [Parameter(Mandatory)]
        [object] $Object,

        [Parameter(Mandatory)]
        [string] $Name
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        throw "Configuration is missing '$Name'."
    }
    return $property.Value
}

function Assert-OnlyProperties {
    param(
        [Parameter(Mandatory)]
        [object] $Object,

        [Parameter(Mandatory)]
        [string[]] $Allowed,

        [Parameter(Mandatory)]
        [string] $Location
    )

    $allowedNames = [Collections.Generic.HashSet[string]]::new(
        $Allowed,
        [StringComparer]::Ordinal
    )
    foreach ($property in $Object.PSObject.Properties) {
        if (!$allowedNames.Contains($property.Name)) {
            throw "Configuration $Location contains an unsupported value."
        }
    }
}

function Get-ValidatedString {
    param(
        [Parameter(Mandatory)]
        [object] $Object,

        [Parameter(Mandatory)]
        [string] $Name,

        [Parameter(Mandatory)]
        [int] $MaximumLength
    )

    $value = Get-PropertyValue $Object $Name
    if ($value -isnot [string]) {
        throw "Configuration value '$Name' must be text."
    }
    $trimmed = $value.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.Length -gt $MaximumLength) {
        throw "Configuration value '$Name' must contain 1 to $MaximumLength characters."
    }
    return $trimmed
}

function Get-ValidatedBoolean {
    param(
        [Parameter(Mandatory)]
        [object] $Object,

        [Parameter(Mandatory)]
        [string] $Name
    )

    $value = Get-PropertyValue $Object $Name
    if ($value -isnot [bool]) {
        throw "Configuration value '$Name' must be true or false."
    }
    return $value
}

function Get-ValidatedText {
    param(
        [Parameter(Mandatory)]
        [object] $Object,

        [Parameter(Mandatory)]
        [string] $Name,

        [Parameter(Mandatory)]
        [int] $MaximumLength
    )

    $value = Get-PropertyValue $Object $Name
    if ($value -isnot [string]) {
        throw "Configuration value '$Name' must be text."
    }
    if ($value.Length -gt $MaximumLength) {
        throw "Configuration value '$Name' cannot exceed $MaximumLength characters."
    }
    return $value
}

function Get-ValidatedStringArray {
    param(
        [Parameter(Mandatory)]
        [object] $Object,

        [Parameter(Mandatory)]
        [string] $Name,

        [Parameter(Mandatory)]
        [int] $MaximumItemLength,

        [switch] $TrimItems,

        [switch] $IgnoreCaseForDuplicates
    )

    $value = Get-PropertyValue $Object $Name
    if ($value -is [string] -or $value -isnot [Collections.IEnumerable]) {
        throw "Configuration value '$Name' must be an array."
    }

    $comparer = if ($IgnoreCaseForDuplicates) {
        [StringComparer]::OrdinalIgnoreCase
    } else {
        [StringComparer]::Ordinal
    }
    $seen = [Collections.Generic.HashSet[string]]::new($comparer)
    $validated = [Collections.Generic.List[string]]::new()
    $sourceCount = 0
    foreach ($item in $value) {
        $sourceCount++
        if ($sourceCount -gt $maximumListEntries) {
            throw "Configuration value '$Name' cannot contain more than $maximumListEntries entries."
        }
        if ($item -isnot [string]) {
            throw "Configuration value '$Name' must contain only text entries."
        }
        $normalized = if ($TrimItems) { $item.Trim() } else { $item }
        if (
            [string]::IsNullOrWhiteSpace($normalized) -or
            $normalized.Length -gt $MaximumItemLength -or
            $normalized.Contains("`r") -or
            $normalized.Contains("`n")
        ) {
            throw "Configuration value '$Name' contains an invalid entry."
        }
        if ($seen.Add($normalized)) {
            $validated.Add($normalized)
        }
    }
    return $validated.ToArray()
}

function ConvertTo-ValidatedConfiguration {
    param(
        [Parameter(Mandatory)]
        [object] $Source
    )

    $configuration = [ordered]@{}
    Assert-OnlyProperties $Source @("masterEnabled", "whatsApp", "telegram", "sms", "filters") "payload"
    $masterEnabledProperty = $Source.PSObject.Properties["masterEnabled"]
    if ($null -ne $masterEnabledProperty) {
        $configuration.masterEnabled = Get-ValidatedBoolean $Source "masterEnabled"
    }

    $whatsAppProperty = $Source.PSObject.Properties["whatsApp"]
    if ($null -ne $whatsAppProperty) {
        $channel = $whatsAppProperty.Value
        if ($null -eq $channel) {
            throw "Configuration value 'whatsApp' must be an object."
        }
        Assert-OnlyProperties $channel @("enabled", "phoneNumberId", "accessToken", "recipient") "whatsApp"
        $configuration.whatsApp = [ordered]@{
            enabled       = Get-ValidatedBoolean $channel "enabled"
            phoneNumberId = Get-ValidatedString $channel "phoneNumberId" 256
            accessToken   = Get-ValidatedString $channel "accessToken" 8192
            recipient     = Get-ValidatedString $channel "recipient" 64
        }
    }

    $telegramProperty = $Source.PSObject.Properties["telegram"]
    if ($null -ne $telegramProperty) {
        $channel = $telegramProperty.Value
        if ($null -eq $channel) {
            throw "Configuration value 'telegram' must be an object."
        }
        Assert-OnlyProperties $channel @("enabled", "botToken", "chatId") "telegram"
        $configuration.telegram = [ordered]@{
            enabled  = Get-ValidatedBoolean $channel "enabled"
            botToken = Get-ValidatedString $channel "botToken" 1024
            chatId   = Get-ValidatedString $channel "chatId" 128
        }
    }

    $smsProperty = $Source.PSObject.Properties["sms"]
    if ($null -ne $smsProperty) {
        $channel = $smsProperty.Value
        if ($null -eq $channel) {
            throw "Configuration value 'sms' must be an object."
        }
        Assert-OnlyProperties $channel @("enabled", "destination") "sms"
        $configuration.sms = [ordered]@{
            enabled     = Get-ValidatedBoolean $channel "enabled"
            destination = Get-ValidatedString $channel "destination" 64
        }
    }

    $filtersProperty = $Source.PSObject.Properties["filters"]
    if ($null -ne $filtersProperty) {
        $sourceFilters = $filtersProperty.Value
        if ($null -eq $sourceFilters) {
            throw "Configuration value 'filters' must be an object."
        }
        Assert-OnlyProperties $sourceFilters @("allowedSenders", "regexes", "forwardTemplate") "filters"
        $filters = [ordered]@{}
        if ($null -ne $sourceFilters.PSObject.Properties["allowedSenders"]) {
            $filters.allowedSenders = @(
                Get-ValidatedStringArray $sourceFilters "allowedSenders" 256 -TrimItems -IgnoreCaseForDuplicates
            )
        }
        if ($null -ne $sourceFilters.PSObject.Properties["regexes"]) {
            $filters.regexes = @(
                Get-ValidatedStringArray $sourceFilters "regexes" 4096
            )
        }
        if ($null -ne $sourceFilters.PSObject.Properties["forwardTemplate"]) {
            $filters.forwardTemplate = Get-ValidatedText $sourceFilters "forwardTemplate" 8192
        }
        if ($filters.Count -eq 0) {
            throw "Configuration value 'filters' does not contain a supported setting."
        }
        $configuration.filters = $filters
    }

    if ($configuration.Count -eq 0) {
        throw "Configuration does not contain a supported setting."
    }
    return $configuration
}

function Read-StringArray {
    param(
        [Parameter(Mandatory)]
        [string] $Prompt,

        [Parameter(Mandatory)]
        [int] $MaximumItemLength,

        [switch] $TrimItems,

        [switch] $IgnoreCaseForDuplicates
    )

    $comparer = if ($IgnoreCaseForDuplicates) {
        [StringComparer]::OrdinalIgnoreCase
    } else {
        [StringComparer]::Ordinal
    }
    $seen = [Collections.Generic.HashSet[string]]::new($comparer)
    $values = [Collections.Generic.List[string]]::new()
    while ($values.Count -lt $maximumListEntries) {
        $item = Read-Host "$Prompt (blank when finished)"
        if ([string]::IsNullOrWhiteSpace($item)) {
            break
        }
        $normalized = if ($TrimItems) { $item.Trim() } else { $item }
        if (
            $normalized.Length -gt $MaximumItemLength -or
            $normalized.Contains("`r") -or
            $normalized.Contains("`n")
        ) {
            Write-Warning "Entry must contain at most $MaximumItemLength characters and no line breaks."
            continue
        }
        if (!$seen.Add($normalized)) {
            Write-Warning "Duplicate entry ignored."
            continue
        }
        $values.Add($normalized)
    }
    return $values.ToArray()
}

function Read-InteractiveConfiguration {
    $configuration = [ordered]@{}

    if (Read-BooleanChoice "Include master forwarding switch setting?" $true) {
        $configuration.masterEnabled = Read-BooleanChoice "Enable master forwarding after import?" $true
    }

    if (Read-BooleanChoice "Include WhatsApp configuration?" $true) {
        $configuration.whatsApp = [ordered]@{
            enabled       = Read-BooleanChoice "Enable WhatsApp after import?" $true
            phoneNumberId = Read-RequiredValue "WhatsApp Phone Number ID" 256
            accessToken   = Read-RequiredSecret "WhatsApp access token (hidden)" 8192
            recipient     = Read-RequiredValue "WhatsApp recipient number" 64
        }
    }

    if (Read-BooleanChoice "Include Telegram configuration?" $true) {
        $configuration.telegram = [ordered]@{
            enabled  = Read-BooleanChoice "Enable Telegram after import?" $false
            botToken = Read-RequiredSecret "Telegram bot token (hidden)" 1024
            chatId   = Read-RequiredValue "Telegram chat ID" 128
        }
    }

    if (Read-BooleanChoice "Include SMS channel configuration?" $false) {
        $configuration.sms = [ordered]@{
            enabled     = Read-BooleanChoice "Enable SMS after import?" $false
            destination = Read-RequiredValue "SMS destination number" 64
        }
    }

    $filters = [ordered]@{}
    if (Read-BooleanChoice "Add allowed senders?" $true) {
        $filters.allowedSenders = @(
            Read-StringArray "Allowed sender" 256 -TrimItems -IgnoreCaseForDuplicates
        )
    }
    if (Read-BooleanChoice "Add message regex rules?" $true) {
        $filters.regexes = @(
            Read-StringArray "Message regex (lowercase and accent-free)" 4096
        )
    }
    if (Read-BooleanChoice "Set the forwarding template?" $false) {
        $filters.forwardTemplate = Read-Host "Forwarding template (%s source, %t time, %m message; blank clears it)"
    }
    if ($filters.Count -gt 0) {
        $configuration.filters = $filters
    }

    if ($configuration.Count -eq 0) {
        throw "At least one setting must be included."
    }
    return $configuration
}

function Get-BundlePassphrase {
    if ($null -ne $Passphrase) {
        $value = ConvertFrom-ProtectedValue $Passphrase
        if ($value.Length -lt $minimumPassphraseLength -or $value.Length -gt $maximumPassphraseLength) {
            throw "Passphrase must contain $minimumPassphraseLength to $maximumPassphraseLength characters."
        }
        return $value
    }

    $first = ConvertFrom-ProtectedValue (Read-Host "Bundle passphrase (hidden)" -AsSecureString)
    if ($first.Length -lt $minimumPassphraseLength -or $first.Length -gt $maximumPassphraseLength) {
        throw "Passphrase must contain $minimumPassphraseLength to $maximumPassphraseLength characters."
    }
    $second = ConvertFrom-ProtectedValue (Read-Host "Confirm bundle passphrase (hidden)" -AsSecureString)
    if (!$first.Equals($second, [StringComparison]::Ordinal)) {
        throw "Passphrases do not match."
    }
    return $first
}

$resolvedOutputPath = Resolve-FullPath $OutputPath
if (![IO.Path]::GetExtension($resolvedOutputPath).Equals(".mcsmsconfig", [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputPath must use the .mcsmsconfig extension."
}
if (Test-PathInsideRepository $resolvedOutputPath) {
    throw "Write provisioning bundles outside the repository."
}
$outputDirectory = [IO.Path]::GetDirectoryName($resolvedOutputPath)
if (!(Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    throw "Output directory does not exist: $outputDirectory"
}
if ((Test-Path -LiteralPath $resolvedOutputPath) -and !$Force) {
    throw "Output file already exists. Use -Force to replace it."
}

$configuration = if ([string]::IsNullOrWhiteSpace($ConfigurationPath)) {
    Read-InteractiveConfiguration
} else {
    $resolvedConfigurationPath = Resolve-FullPath $ConfigurationPath
    if (Test-PathInsideRepository $resolvedConfigurationPath) {
        throw "Keep plaintext configuration files outside the repository."
    }
    if (!(Test-Path -LiteralPath $resolvedConfigurationPath -PathType Leaf)) {
        throw "Configuration file does not exist: $resolvedConfigurationPath"
    }
    try {
        $source = Get-Content -LiteralPath $resolvedConfigurationPath -Raw -Encoding UTF8 | ConvertFrom-Json -Depth 16
    } catch [System.Management.Automation.RuntimeException] {
        throw "Could not parse the plaintext configuration JSON."
    }
    ConvertTo-ValidatedConfiguration $source
}

$passphraseText = Get-BundlePassphrase
$salt = [byte[]]::new($saltLength)
$nonce = [byte[]]::new($nonceLength)
[Security.Cryptography.RandomNumberGenerator]::Fill($salt)
[Security.Cryptography.RandomNumberGenerator]::Fill($nonce)

$payloadJson = $configuration | ConvertTo-Json -Compress -Depth 8
$plaintext = [Text.Encoding]::UTF8.GetBytes($payloadJson)
$keyDeriver = [Security.Cryptography.Rfc2898DeriveBytes]::new(
    $passphraseText,
    $salt,
    $kdfIterations,
    [Security.Cryptography.HashAlgorithmName]::SHA256
)

$key = $null
$ciphertext = [byte[]]::new($plaintext.Length)
$tag = [byte[]]::new($tagLength)
try {
    $key = $keyDeriver.GetBytes($keyLength)
    $aes = [Security.Cryptography.AesGcm]::new($key)
    try {
        $aes.Encrypt($nonce, $plaintext, $ciphertext, $tag, $associatedData)
    } finally {
        $aes.Dispose()
    }
} finally {
    $keyDeriver.Dispose()
    [Array]::Clear($plaintext, 0, $plaintext.Length)
    if ($null -ne $key) {
        [Array]::Clear($key, 0, $key.Length)
    }
    $passphraseText = $null
    $payloadJson = $null
    $configuration = $null
}

$envelope = [ordered]@{
    format  = $formatName
    version = $formatVersion
    kdf     = [ordered]@{
        algorithm  = $kdfAlgorithm
        iterations = $kdfIterations
        salt       = [Convert]::ToBase64String($salt)
    }
    cipher  = [ordered]@{
        algorithm  = $cipherAlgorithm
        nonce      = [Convert]::ToBase64String($nonce)
        ciphertext = [Convert]::ToBase64String($ciphertext)
        tag        = [Convert]::ToBase64String($tag)
    }
}

$temporaryPath = Join-Path $outputDirectory ".$([IO.Path]::GetFileName($resolvedOutputPath)).$([Guid]::NewGuid().ToString('N')).tmp"
try {
    $json = $envelope | ConvertTo-Json -Compress -Depth 8
    if ([Text.Encoding]::UTF8.GetByteCount($json) -gt $maximumBundleBytes) {
        throw "Encrypted provisioning bundle exceeds the $maximumBundleBytes-byte application limit."
    }
    [IO.File]::WriteAllText($temporaryPath, $json, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryPath -Destination $resolvedOutputPath -Force:$Force
} finally {
    if (Test-Path -LiteralPath $temporaryPath) {
        Remove-Item -LiteralPath $temporaryPath -Force
    }
}

Write-Host "Encrypted provisioning bundle created at $resolvedOutputPath"
