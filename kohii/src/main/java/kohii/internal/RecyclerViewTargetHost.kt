/*
 * Copyright (c) 2019 Nam Nguyen, nam@ene.im
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

package kohii.internal

import android.view.View
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecycleViewUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kohii.v1.Playback
import kohii.v1.PlaybackManager
import java.lang.ref.WeakReference
import kotlin.LazyThreadSafetyMode.NONE

internal class RecyclerViewTargetHost(
  host: RecyclerView,
  manager: PlaybackManager,
  selector: Selector? = null
) : BaseTargetHost<RecyclerView>(host, manager, selector) {

  companion object {
    fun RecyclerView.fetchOrientation(): Int {
      return when (val layout = this.layoutManager ?: return NONE_AXIS) {
        is LinearLayoutManager -> layout.orientation
        is StaggeredGridLayoutManager -> layout.orientation
        else -> {
          return if (layout.canScrollVertically()) {
            if (layout.canScrollHorizontally()) BOTH_AXIS
            else VERTICAL
          } else {
            if (layout.canScrollHorizontally()) HORIZONTAL
            else NONE_AXIS
          }
        }
      }
    }
  }

  private val scrollListener by lazy(NONE) { SimpleOnScrollListener(manager) }

  override fun onAdded() {
    super.onAdded()
    actualHost.addOnScrollListener(scrollListener)
    actualHost.doOnLayout {
      if (actualHost.scrollState == SCROLL_STATE_IDLE) manager.dispatchRefreshAll()
    }
  }

  override fun onRemoved() {
    super.onRemoved()
    actualHost.removeOnScrollListener(scrollListener)
  }

  override fun allowsToPlay(playback: Playback<*>): Boolean {
    val container = playback.container
    return container is View &&
        this.actualHost.findContainingViewHolder(container) != null &&
        playback.token.shouldPlay()
  }

  override fun accepts(container: Any): Boolean {
    if (container !is View) return false
    val params = RecycleViewUtils.fetchItemViewParams(container)
    return RecycleViewUtils.checkParams(actualHost, params)
  }

  override fun select(candidates: Collection<Playback<*>>): Collection<Playback<*>> {
    if (selector != null) return selector.select(candidates)
    return super.selectByOrientation(candidates, actualHost.fetchOrientation())
  }

  private class SimpleOnScrollListener(manager: PlaybackManager) : OnScrollListener() {
    val weakManager = WeakReference(manager)

    override fun onScrolled(
      recyclerView: RecyclerView,
      dx: Int,
      dy: Int
    ) {
      weakManager.get()
          ?.dispatchRefreshAll()
    }
  }
}
