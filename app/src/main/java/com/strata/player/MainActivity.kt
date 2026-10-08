package com.strata.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.strata.player.audio.PlaybackService
import com.strata.player.ui.StrataRoot

class MainActivity : ComponentActivity() {

    private var controller: ListenableFuture<MediaController>? = null

    private val model: AppModel get() = (application as StrataApp).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.onPermission(hasAudioPermission())
        setContent {
            StrataRoot(model = model, onRequestPermission = ::askPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller starts the session service, so playback keeps going in the background.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
    }

    override fun onResume() {
        super.onResume()
        val granted = hasAudioPermission()
        if (granted != model.hasPermission) model.onPermission(granted)
    }

    override fun onStop() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(audioPermission(), Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(audioPermission())
        }
        permissionLauncher.launch(perms)
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { model.onPermission(hasAudioPermission()) }
}
