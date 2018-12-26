/*
 * Copyright (c) 2018 Nam Nguyen, nam@ene.im
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kohii.v1

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver.OnScrollChangedListener
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.google.android.exoplayer2.ExoPlayerLibraryInfo.VERSION_SLASHY
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import kohii.media.Media
import kohii.media.MediaItem
import kohii.v1.exo.DefaultBandwidthMeterFactory
import kohii.v1.exo.DefaultBridgeProvider
import kohii.v1.exo.DefaultDrmSessionManagerProvider
import kohii.v1.exo.DefaultMediaSourceFactoryProvider
import kohii.v1.exo.DefaultPlayerProvider
import kohii.v1.exo.MediaSourceFactoryProvider
import kohii.v1.exo.PlayerProvider
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author eneim (2018/06/24).
 */
class Kohii(context: Context) {

  internal val app = context.applicationContext as Application

  // Map between Context with the Manager that is hosted on it.
  internal val mapWeakContextToManager = WeakHashMap<Context, Manager>()

  // Which Playable is managed by which Manager
  internal val mapWeakPlayableToManager = WeakHashMap<Playable, Manager?>()

  // Store playable whose tag is available. Non tagged playable are always ignored.
  internal val mapTagToPlayable = HashMap<Any /* ⬅ playable tag */, Playable>()

  internal val bridgeProvider: BridgeProvider

  private val playerProvider: PlayerProvider
  private val mediaSourceFactoryProvider: MediaSourceFactoryProvider

  init {
    app.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
      }

      override fun onActivityStarted(activity: Activity) {
        mapWeakContextToManager[activity]?.onHostStarted()
      }

      override fun onActivityResumed(activity: Activity) {
        // ignored
      }

      override fun onActivityPaused(activity: Activity) {
        // ignored
      }

      // Note [20181021]: (eneim) Considered to free unused resource here, due to the nature of onDestroy.
      // But this method is also called when User click to "Current Apps" thing, and releasing
      // stuff there is not good for UX.
      override fun onActivityStopped(activity: Activity) {
        mapWeakContextToManager[activity]?.onHostStopped(activity.isChangingConfigurations)
      }

      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
      }

      // Note [20180527]: (eneim) This method is called before DecorView is detached.
      // Note [20181021]: (eneim) I also try to clean up any unused Player instances here.
      // I acknowledge that onDestroy() can be ignored by System. But it is the case where the
      // whole process is also destroyed. So nothing on-memory will remain.
      override fun onActivityDestroyed(activity: Activity) {
        mapWeakContextToManager.remove(activity)?.let {
          it.onHostDestroyed()
          it.onDetached() // Manager should not outlive Activity, any second.
        }

        // Debug only.
        if (BuildConfig.DEBUG) {
          mapWeakPlayableToManager.forEach {
            if (it.value == null) {
              Log.w(TAG, "onActivityDestroyed(): ${it.key} -- ${it.value}")
            } else {
              Log.d(TAG, "onActivityDestroyed(): ${it.key} -- ${it.value}")
            }
          }
        }

        // Activity is not being recreated, and there is no Manager available.
        if (!activity.isChangingConfigurations && mapWeakContextToManager.isEmpty()) {
          this@Kohii.cleanUp()
        }
      }
    })

    // Shared dependencies.
    val userAgent = getUserAgent(this.app, BuildConfig.LIB_NAME)
    val bandwidthMeter = DefaultBandwidthMeter()
    val httpDataSource = DefaultHttpDataSourceFactory(userAgent, bandwidthMeter.transferListener)

    // ExoPlayerProvider
    val drmSessionManagerProvider = DefaultDrmSessionManagerProvider(this.app, httpDataSource)
    playerProvider = DefaultPlayerProvider(
        this.app,
        DefaultBandwidthMeterFactory(),
        drmSessionManagerProvider
    )

    // MediaSourceFactoryProvider
    var tempDir = this.app.getExternalFilesDir(null)
    if (tempDir == null) tempDir = this.app.filesDir
    val downloadDir = tempDir
    val contentDir = File(downloadDir, DOWNLOAD_CONTENT_DIRECTORY)
    val mediaCache = SimpleCache(contentDir, LeastRecentlyUsedCacheEvictor(CACHE_SIZE))
    val upstreamFactory = DefaultDataSourceFactory(this.app, httpDataSource)
    mediaSourceFactoryProvider = DefaultMediaSourceFactoryProvider(upstreamFactory, mediaCache)

    bridgeProvider = DefaultBridgeProvider(playerProvider, mediaSourceFactoryProvider)
  }

  internal class ManagerAttachStateListener(context: Context, val view: View) :
      View.OnAttachStateChangeListener {

    private val weakContext = WeakReference(context)

    override fun onViewAttachedToWindow(v: View) {
      kohii!!.mapWeakContextToManager[weakContext.get()]?.onAttached()
    }

    // Will get called after Activity's onDestroy().
    override fun onViewDetachedFromWindow(v: View) {
      // The next line may not be called, we may already remove this in onActivityDestroyed.
      kohii!!.mapWeakContextToManager.remove(weakContext.get())?.onDetached()
      if (this.view === v) this.view.removeOnAttachStateChangeListener(this)
    }
  }

  // TODO [20181022] Is it safe to use this class for Activity's lifetime?
  internal class GlobalScrollChangeListener(val manager: Manager) : OnScrollChangedListener {

    private val scrollConsumed = AtomicBoolean(false)
    val managerRef = WeakReference(manager)

    private val handler: Handler = object : Handler(Looper.getMainLooper()) {
      override fun handleMessage(msg: Message) {
        val manager = managerRef.get() ?: return
        when (msg.what) {
          EVENT_SCROLL -> {
            manager.setScrolling(true)
            manager.dispatchRefreshAll()
            this.sendEmptyMessageDelayed(EVENT_IDLE, EVENT_DELAY)
          }
          EVENT_IDLE -> {
            manager.setScrolling(false)
            manager.dispatchRefreshAll()
          }
        }
      }
    }

    override fun onScrollChanged() {
      scrollConsumed.set(false)
      handler.removeMessages(EVENT_IDLE)
      handler.removeMessages(EVENT_SCROLL)
      handler.sendEmptyMessageDelayed(EVENT_SCROLL, EVENT_DELAY)
    }

    companion object {
      private const val EVENT_SCROLL = 1
      private const val EVENT_IDLE = 2
      private const val EVENT_DELAY = (3 * 1000 / 60).toLong()  // 50 ms, 3 frames
    }
  }

  companion object {
    private const val TAG = "Kohii:X"
    private const val DOWNLOAD_CONTENT_DIRECTORY = "kohii_content"
    private const val CACHE_SIZE = 32 * 1024 * 1024L // 32 Megabytes

    @Volatile
    private var kohii: Kohii? = null

    // So we can call Kohii[context] instead of Kohii.get(context).
    operator fun get(context: Context) = kohii ?: synchronized(Kohii::class.java) {
      kohii ?: Kohii(context).also { kohii = it }
    }

    operator fun get(fragment: Fragment) = get(fragment.requireActivity())

    internal fun getUserAgent(context: Context, appName: String): String {
      val versionName = try {
        val packageName = context.packageName
        val info = context.packageManager.getPackageInfo(packageName, 0)
        info.versionName
      } catch (e: Exception) {
        "?"
      }

      return "$appName/$versionName (Linux;Android ${Build.VERSION.RELEASE}) $VERSION_SLASHY"
    }
  }

  //// instance methods

  // TODO consider how to support Dialog (Dialog has different Window to Activity).
  internal fun getManager(dialog: Dialog): Manager {
    val window = dialog.window as Window
    val decorView = window.peekDecorView() // peek to read, not create
        ?: throw IllegalStateException("DecorView is null for ${window.context}")
    return mapWeakContextToManager[window.context] ?: //
    Manager.Builder(this, decorView).build().also {
      mapWeakContextToManager[window.context] = it
      if (ViewCompat.isAttachedToWindow(decorView)) it.onAttached()
      decorView.addOnAttachStateChangeListener(
          ManagerAttachStateListener(window.context, decorView))
    }
  }

  internal fun getManager(context: Context): Manager {
    // TODO [20181027] Consider the case of Dialog (has different Window with the host Activity)
    if (context !is Activity) {
      throw RuntimeException("Expect Activity, got: " + context.javaClass.simpleName)
    }

    val decorView = context.window.peekDecorView()  // peek to read, not create
        ?: throw IllegalStateException("DecorView is null for $context")
    return mapWeakContextToManager[context] ?: //
    Manager.Builder(this, decorView).build().also {
      mapWeakContextToManager[context] = it
      if (ViewCompat.isAttachedToWindow(decorView)) it.onAttached()
      decorView.addOnAttachStateChangeListener(ManagerAttachStateListener(context, decorView))
    }
  }

  // Called when a Playable is no longer be managed by any Manager, its resource should be release.
  // Always get called after playable.release()
  internal fun releasePlayable(tag: Any?, playable: Playable) {
    tag?.run {
      if (mapTagToPlayable.remove(tag) != playable) {
        throw IllegalArgumentException("Illegal playable removal: $playable")
      }
    } ?: playable.release()
  }

  /** Called when a Manager becomes active to a Playback that it already manages. */
  internal fun onManagerActiveForPlayback(manager: Manager, playback: Playback<*>) {
    mapWeakPlayableToManager[playback.playable] = manager
    // Check if the Playable is already bound to the Playback's target.
    if (manager.mapWeakPlayableToTarget[playback.playable] == playback.target) {
      manager.restorePlaybackInfo(playback)
      // TODO [20180905] double check the usage of 'shouldPrepare()' here.
      if (playback.token?.shouldPrepare() == true) playback.prepare()
    }
    /* if (manager.mapAttachedPlaybackToTime[playback] != null) <-- always true */ playback.onActive()
  }

  /**
   * Called when the Activity mapped to a Manager is stopped. When called, a [Playback]'s target is
   * considered 'unavailable' even if it is not detached yet (in case the target is a [View])
   *
   * @param manager The [Manager] whose Activity is stopped.
   * @param playback The [Playback] that is managed by the manager.
   * @param configChange If true, the Activity is being recreated by a config change, false otherwise.
   */
  internal fun onManagerInActiveForPlayback(
      manager: Manager,
      playback: Playback<*>,
      configChange: Boolean
  ) {
    val playable = playback.playable
    // Only pause this playback if [1] config change is happening and [2] the playable is managed
    // by this manager, or by no-one else.
    // FYI: The Playable instances holds the actual playback resource. It is not managed by anything
    // else when the Activity is destroyed and to be recreated (config change).
    if (mapWeakPlayableToManager[playable] == manager || mapWeakPlayableToManager[playable] == null) {
      if (!configChange) {
        playback.pause()
        manager.savePlaybackInfo(playback)
      }
    }
    /* if (manager.mapAttachedPlaybackToTime[playback] != null) <-- always true */ playback.onInActive()
    // There is no recreation. If the manager is managing the playable, unload the Playable.
    if (!configChange) {
      if (mapWeakPlayableToManager[playable] == manager) mapWeakPlayableToManager[playable] = null
    }
  }

  // Gat called when Kohii should free all resources.
  // FIXME [20180805] Now, this is called when activity destroy is detected, and there is no further
  // Manager to be alive. In the future, we may consider to have non-Activity Manager.
  internal fun cleanUp() {
    (playerProvider as DefaultPlayerProvider).cleanUp()
  }

  //// [BEGIN] Public API

  fun setUp(uri: Uri): Playable.Builder {
    return this.setUp(MediaItem(uri))
  }

  fun setUp(url: String): Playable.Builder {
    return this.setUp(MediaItem(Uri.parse(url)))
  }

  fun setUp(media: Media): Playable.Builder {
    return Playable.Builder(this, media = media)
  }

  // Find a Playable for a tag. Single player may use this for full-screen playback.
  // TODO [20180719] returned Playable may still be managed by a Manager. Need to know why.
  fun findPlayable(tag: Any?): Playable? {
    return this.mapTagToPlayable[tag].also {
      mapWeakPlayableToManager[it] = null // once found, it will be detached from the last Manager.
    }
  }

  //// [END] Public API
}