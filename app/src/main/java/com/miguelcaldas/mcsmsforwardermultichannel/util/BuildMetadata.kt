package com.miguelcaldas.mcsmsforwardermultichannel.util

import com.miguelcaldas.mcsmsforwardermultichannel.BuildConfig
import com.miguelcaldas.mcsmsforwardermultichannel.GeneratedBuildMetadata
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object BuildMetadata {
    private val UTC_FORMATTER = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)

    val title: String
        get() = "MC SMS Forwarder v${BuildConfig.VERSION_NAME}"

    val details: String
        get() = "Built ${formatUtc(GeneratedBuildMetadata.BUILD_TIME_EPOCH_MILLIS)} \u00b7 " +
            GeneratedBuildMetadata.SOURCE_REVISION

    fun formatUtc(epochMillis: Long): String = UTC_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
}
