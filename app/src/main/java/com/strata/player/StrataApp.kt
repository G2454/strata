package com.strata.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.strata.player.ui.AudioArtFetcher
import com.strata.player.ui.AudioArtKeyer

class StrataApp : Application(), ImageLoaderFactory {

    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        model = AppModel(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(AudioArtKeyer())
            add(AudioArtFetcher.Factory())
        }
        .crossfade(180)
        .build()
}
