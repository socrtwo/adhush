package io.adhush.android

import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/** Quick Settings tile: the fastest "you muted my show" correction. */
class NotAdTileService : TileService() {
    override fun onClick() {
        ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(AdHushService.ACTION_NOT_AD))
    }
}
