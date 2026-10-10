/*
 * Copyright (C) 2024 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.common.overlays.menu

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton

import androidx.annotation.CallSuper
import androidx.annotation.IdRes
import androidx.annotation.StyleRes
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle

import com.buzbuz.smartautoclicker.core.base.addDumpTabulationLvl
import com.buzbuz.smartautoclicker.core.base.extensions.disableMoveAnimations
import com.buzbuz.smartautoclicker.core.base.extensions.doWhenMeasured
import com.buzbuz.smartautoclicker.core.base.extensions.safeAddView
import com.buzbuz.smartautoclicker.core.base.extensions.safeRemoveView
import com.buzbuz.smartautoclicker.core.base.extensions.safeUpdateViewLayout
import com.buzbuz.smartautoclicker.core.common.overlays.R
import com.buzbuz.smartautoclicker.core.common.overlays.base.BaseOverlay
import com.buzbuz.smartautoclicker.core.common.overlays.di.OverlaysEntryPoint
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common.OverlayMenuAnimations
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common.OverlayMenuMoveTouchEventHandler
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common.OverlayMenuPositionDataSource
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common.OverlayMenuResizeController

import dagger.hilt.EntryPoints
import java.io.PrintWriter

/**
 * Controller for a menu displayed as an overlay shown from a service.
 */
abstract class OverlayMenu(
    @StyleRes theme: Int? = null,
    private val recreateOverlayViewOnRotation: Boolean = false,
) : BaseOverlay(theme = theme, recreateOnRotation = false) {

    /** The base layout parameters of the menu layout & overlay view. */
    private val baseLayoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        OverlayManager.OVERLAY_WINDOW_TYPE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        disableMoveAnimations()
    }

    /** The layout parameters of the menu layout. */
    private val menuLayoutParams: WindowManager.LayoutParams =
        WindowManager.LayoutParams().apply { copyFrom(baseLayoutParams) }

    private val animations: OverlayMenuAnimations = OverlayMenuAnimations()

    internal var resumeOnceShown: Boolean = false
        private set
    internal var destroyOnceHidden: Boolean = false
        private set

    /** The Android window manager. Used to add/remove the overlay menu and view. */
    private lateinit var windowManager: WindowManager

    /** The root view of the menu overlay. Retrieved from [onCreateMenu] implementation. */
    private lateinit var menuLayout: ViewGroup
    /** The view displaying the background of the overlay. */
    private lateinit var menuBackground: ViewGroup
    /** The view containing the buttons as direct children. */
    private lateinit var buttonsContainer: ViewGroup
    /** Handles the window size computing when animating a resize of the overlay. */
    private lateinit var resizeController: OverlayMenuResizeController
    /** Handles the touch events on the move button. */
    private lateinit var moveTouchEventHandler: OverlayMenuMoveTouchEventHandler

    /** Handles the save/load of the position of the menus. */
    private val positionDataSource: OverlayMenuPositionDataSource by lazy {
        EntryPoints.get(context.applicationContext, OverlaysEntryPoint::class.java)
            .overlayMenuPositionDataSource()
    }

    /** Value of the alpha for a disabled item view in the menu. */
    private var disabledItemAlpha: Float = 1f

    /** The hide overlay button, if provided. */
    private var hideOverlayButton: ImageButton? = null
    /** The move button, if provided. */
    private var moveButton: View? = null
    /** The duck toggle button, if provided. */
    private var duckToggleButton: View? = null

    /**
     * The view to be displayed between the current activity and the overlay menu.
     */
    protected var screenOverlayView: View? = null
    /** The layout parameters of the overlay view. */
    private lateinit var overlayLayoutParams: WindowManager.LayoutParams

    private val onLockedPositionChangedListener: (Point?) -> Unit = ::onLockedPositionChanged

    protected abstract fun onCreateMenu(layoutInflater: LayoutInflater): ViewGroup
    protected open fun onCreateOverlayView(): View? = null
    protected open fun animateOverlayView(): Boolean = true

    protected open fun onCreateOverlayViewLayoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        copyFrom(baseLayoutParams)
        displayConfigManager.displayConfig.sizePx.let { size ->
            width = size.x
            height = size.y
        }
    }

    @CallSuper
    @SuppressLint("ResourceType")
    override fun onCreate() {
        windowManager = context.getSystemService(WindowManager::class.java)!!
        disabledItemAlpha = context.resources.getFraction(R.dimen.alpha_menu_item_disabled, 1, 1)

        menuLayout = onCreateMenu(context.getSystemService(LayoutInflater::class.java))
        screenOverlayView = onCreateOverlayView()
        overlayLayoutParams = onCreateOverlayViewLayoutParams()

        menuBackground = menuLayout.findViewById(R.id.menu_background)
        buttonsContainer = menuLayout.findViewById(R.id.menu_items)
        setupButtons(buttonsContainer)

        moveTouchEventHandler = OverlayMenuMoveTouchEventHandler(::updateMenuPosition)

        menuLayoutParams.gravity = Gravity.TOP or Gravity.START
        overlayLayoutParams.gravity = Gravity.TOP or Gravity.START
        positionDataSource.addOnLockedPositionChangedListener(onLockedPositionChangedListener)
        loadMenuPosition(displayConfigManager.displayConfig.orientation)
        moveButton?.isVisible = !positionDataSource.isPositionLocked()

        resizeController = OverlayMenuResizeController(
            backgroundViewGroup = menuBackground,
            resizedContainer = buttonsContainer,
            maximumSize = getWindowMaximumSize(menuBackground),
            windowResizer = ::onNewWindowSize,
        )

        screenOverlayView?.let {
            if (animateOverlayView()) it.visibility = View.GONE
            if (!windowManager.safeAddView(it, overlayLayoutParams)) {
                finish()
                return
            }
        }

        if (animateOverlayView()) menuBackground.visibility = View.GONE
        if (!windowManager.safeAddView(menuLayout, menuLayoutParams)) {
            finish()
            return
        }
    }

    private fun setupButtons(buttonsContainer: ViewGroup) {
        buttonsContainer.forEach { view ->
            @SuppressLint("ClickableViewAccessibility")
            when (view.id) {
                R.id.btn_move -> {
                    moveButton = view
                    view.setOnTouchListener { _: View, event: MotionEvent -> onMoveTouched(event) }
                }
                R.id.btn_hide_overlay -> {
                    hideOverlayButton = (view as ImageButton)
                    setOverlayViewVisibility(true)
                    view.setOnClickListener { onToggleOverlayVisibilityClicked() }
                }
                R.id.button_duck_toggle -> {
                    duckToggleButton = view
                    view.setOnClickListener { v ->
                        if (resizeController.isAnimating) return@setOnClickListener
                        // Toggle ẩn/hiện tất cả các nút khác, giữ nguyên nút con vịt 🦆
                        var isAnyChildVisible = false
                        buttonsContainer.forEach { child ->
                            if (child.id != R.id.button_duck_toggle && child.isVisible) {
                                isAnyChildVisible = true
                            }
                        }
                        val makeVisible = !isAnyChildVisible
                        buttonsContainer.forEach { child ->
                            if (child.id != R.id.button_duck_toggle) {
                                child.isVisible = makeVisible
                            }
                        }
                        if (canResizeWindow()) forceWindowResize()
                    }
                }
                else -> view.setDebouncedOnClickListener { v ->
                    if (resizeController.isAnimating) return@setDebouncedOnClickListener
                    onMenuItemClicked(v.id)
                }
            }
        }
    }

    final override fun start() {
        if (lifecycle.currentState != Lifecycle.State.CREATED) return
        if (animations.showAnimationIsRunning) return

        super.start()
        loadMenuPosition(displayConfigManager.displayConfig.orientation)

        Log.d(TAG, "Start show overlay ${hashCode()} animation...")

        val animatedOverlayView = if (animateOverlayView()) screenOverlayView else null
        menuLayout.visibility = View.VISIBLE
        menuBackground.visibility = View.VISIBLE
        animatedOverlayView?.visibility = View.VISIBLE
        animations.startShowAnimation(menuBackground, animatedOverlayView) {
            Log.d(TAG, "Show overlay ${hashCode()} animation ended")

            if (resumeOnceShown) {
                resumeOnceShown = false
                resume()
            }
        }
    }

    final override fun resume() {
        if (lifecycle.currentState == Lifecycle.State.CREATED) start()
        if (lifecycle.currentState != Lifecycle.State.STARTED) return

        if (animations.showAnimationIsRunning) {
            resumeOnceShown = true
            return
        }

        forceWindowResize()
        super.resume()
    }

    final override fun stop() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (animations.hideAnimationIsRunning) return
        if (lifecycle.currentState == Lifecycle.State.RESUMED) pause()

        saveMenuPosition(displayConfigManager.displayConfig.orientation)

        val animatedOverlayView = if (animateOverlayView()) screenOverlayView else null
        animations.startHideAnimation(menuBackground, animatedOverlayView) {
            menuLayout.visibility = View.GONE
            menuBackground.visibility = View.GONE
            screenOverlayView?.visibility = View.GONE

            super.stop()

            if (destroyOnceHidden) {
                destroyOnceHidden = false
                destroy()
            }
        }
    }

    final override fun destroy() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) stop()

        if (animations.hideAnimationIsRunning) {
            destroyOnceHidden = true
            return
        }

        positionDataSource.removeOnLockedPositionChangedListener(onLockedPositionChangedListener)
        saveMenuPosition(displayConfigManager.displayConfig.orientation)

        windowManager.safeRemoveView(menuLayout)
        screenOverlayView?.let { windowManager.safeRemoveView(it) }
        screenOverlayView = null

        resizeController.release()
        super@OverlayMenu.destroy()
    }

    override fun onOrientationChanged() {
        saveMenuPosition(
            if (displayConfigManager.displayConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_PORTRAIT
            else Configuration.ORIENTATION_LANDSCAPE
        )
        loadMenuPosition(displayConfigManager.displayConfig.orientation)

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            windowManager.safeUpdateViewLayout(menuLayout, menuLayoutParams)

            val overlayView = screenOverlayView ?: return
            if (recreateOverlayViewOnRotation) {
                recreateOverlayViewForRotation(overlayView)
                return
            }

            displayConfigManager.displayConfig.sizePx.let { size ->
                overlayLayoutParams.width = size.x
                overlayLayoutParams.height = size.y
            }
            windowManager.safeUpdateViewLayout(overlayView, overlayLayoutParams)
        }
    }

    private fun recreateOverlayViewForRotation(oldOverlayView: View) {
        screenOverlayView = onCreateOverlayView()
        overlayLayoutParams = onCreateOverlayViewLayoutParams().apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val previousState = lifecycle.currentState
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager.apply {
            safeRemoveView(oldOverlayView)
            safeRemoveView(menuLayout)
            screenOverlayView?.let { overlayView ->
                if (!safeAddView(overlayView, overlayLayoutParams)) {
                    finish()
                    return
                }
            }

            if (!safeAddView(menuLayout, menuLayoutParams)) {
                finish()
                return
            }
        }

        lifecycleRegistry.currentState = previousState
        setOverlayViewVisibility(oldOverlayView.isVisible)
    }

    protected open fun onMenuItemClicked(@IdRes viewId: Int): Unit = Unit
    protected open fun onScreenOverlayVisibilityChanged(isVisible: Boolean): Unit = Unit

    protected open fun getWindowMaximumSize(backgroundView: ViewGroup): Size {
        backgroundView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        return Size(backgroundView.measuredWidth, backgroundView.measuredHeight)
    }

    protected fun setMenuVisibility(visibility: Int) {
        menuLayout.visibility = visibility
    }

    protected fun setMenuItemViewEnabled(view: View, enabled: Boolean, clickable: Boolean = false) {
        view.apply {
            isEnabled = enabled || clickable
            alpha = if (enabled) 1.0f else disabledItemAlpha
        }
    }

    protected fun setMenuItemVisibility(view: View, visible: Boolean) {
        if (view.isVisible == visible) return
        view.isVisible = visible
        if (canResizeWindow()) forceWindowResize()
    }

    protected fun setMenuItemsVisibility(viewState: Map<View, Boolean>) {
        var haveChanged = false
        viewState.forEach { (view, isVisible) ->
            haveChanged = haveChanged || view.isVisible != isVisible
            view.isVisible = isVisible
        }

        if (!haveChanged) return
        if (canResizeWindow()) forceWindowResize()
    }

    protected fun animateLayoutChanges(layoutChanges: () -> Unit) {
        resizeController.animateLayoutChanges(layoutChanges)
    }

    private fun canResizeWindow(): Boolean =
        !resizeController.isAnimating && !animations.showAnimationIsRunning
                && !animations.hideAnimationIsRunning && menuBackground.width > 0

    private fun forceWindowResize() {
        onNewWindowSize(resizeController.measureMenuSize())
    }

    private fun onNewWindowSize(size: Size) {
        menuLayoutParams.width = size.width
        menuLayoutParams.height = size.height

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            windowManager.safeUpdateViewLayout(menuLayout, menuLayoutParams)
        }
    }

    private fun onToggleOverlayVisibilityClicked() {
        if (resizeController.isAnimating) return
        screenOverlayView?.let { view ->
            setOverlayViewVisibility(view.visibility != View.VISIBLE)
        }
    }

    protected fun setOverlayViewVisibility(isOverlayVisible: Boolean) {
        screenOverlayView?.apply {
            if (isOverlayVisible) {
                visibility = View.VISIBLE
                hideOverlayButton?.setImageResource(R.drawable.ic_visible_on)
            } else {
                visibility = View.GONE
                hideOverlayButton?.setImageResource(R.drawable.ic_visible_off)
            }
            onScreenOverlayVisibilityChanged(isOverlayVisible)
        }
    }

    private fun onMoveTouched(event: MotionEvent) : Boolean {
        if (resizeController.isAnimating) return false
        return moveTouchEventHandler.onTouchEvent(menuLayout, event)
    }

    private fun updateMenuPosition(position: Point) {
        val displaySize = displayConfigManager.displayConfig.sizePx
        if (displaySize.x < menuLayout.width || displaySize.y < menuLayout.height) return

        menuLayoutParams.x = position.x.coerceIn(0, displaySize.x - menuLayout.width)
        menuLayoutParams.y = position.y.coerceIn(0, displaySize.y - menuLayout.height)

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            windowManager.safeUpdateViewLayout(menuLayout, menuLayoutParams)
        }
    }

    private fun loadMenuPosition(orientation: Int) {
        val savedPosition = positionDataSource.loadMenuPosition(orientation)
        if (savedPosition != null && savedPosition.x != 0 && savedPosition.y != 0) {
            updateMenuPosition(savedPosition)
        } else {
            menuLayout.doWhenMeasured {
                updateMenuPosition(
                    Point(
                        (displayConfigManager.displayConfig.sizePx.x - menuLayout.width) / 2,
                        (displayConfigManager.displayConfig.sizePx.y / 2) - menuLayout.height,
                    )
                )
            }
        }
    }

    private fun saveMenuPosition(orientation: Int) {
        positionDataSource.saveMenuPosition(
            position = Point(menuLayoutParams.x, menuLayoutParams.y),
            orientation = orientation,
        )
    }

    private fun onLockedPositionChanged(lockedPosition: Point?) {
        if (lockedPosition != null) {
            moveButton?.let { setMenuItemVisibility(it, false) }
            saveMenuPosition(displayConfigManager.displayConfig.orientation)
            updateMenuPosition(lockedPosition)
        } else {
            moveButton?.let { setMenuItemVisibility(it, true) }
            loadMenuPosition(displayConfigManager.displayConfig.orientation)
        }
    }

    override fun dump(writer: PrintWriter, prefix: CharSequence) {
        super.dump(writer, prefix)
        val contentPrefix = prefix.addDumpTabulationLvl()

        writer.apply {
            append(contentPrefix)
                .append("resumeOnceShown=$resumeOnceShown; ")
                .append("destroyOnceHidden=$destroyOnceHidden; ")
                .println()

            animations.dump(writer, contentPrefix)
            positionDataSource.dump(writer, contentPrefix)
        }
    }
}

/** Tag for logs */
private const val TAG = "OverlayMenu"
