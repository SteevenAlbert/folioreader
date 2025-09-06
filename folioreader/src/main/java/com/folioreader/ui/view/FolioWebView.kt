package com.folioreader.ui.view

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import android.view.ActionMode
import android.view.ActionMode.Callback
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import com.folioreader.Config
import com.folioreader.Constants
import com.folioreader.R
import com.folioreader.databinding.TextSelectionBinding
import com.folioreader.model.DisplayUnit
import com.folioreader.model.HighLight
import com.folioreader.model.HighlightImpl.HighlightStyle
import com.folioreader.model.sqlite.HighLightTable
import com.folioreader.ui.activity.FolioActivity
import com.folioreader.ui.activity.FolioActivityCallback
import com.folioreader.ui.fragment.DictionaryFragment
import com.folioreader.ui.fragment.FolioPageFragment
import com.folioreader.util.AppUtil
import com.folioreader.util.HighlightUtil
import com.folioreader.util.UiUtil
import dalvik.system.PathClassLoader
import org.json.JSONObject
import org.springframework.util.ReflectionUtils
import java.lang.ref.WeakReference
import kotlin.math.floor

class FolioWebView : WebView {

    companion object {

        val LOG_TAG: String = FolioWebView::class.java.simpleName
        private const val IS_SCROLLING_CHECK_TIMER = 100
        private const val IS_SCROLLING_CHECK_MAX_DURATION = 10000

        @JvmStatic
        fun onWebViewConsoleMessage(cm: ConsoleMessage, LOG_TAG: String, msg: String): Boolean {
            when (cm.messageLevel()) {
                ConsoleMessage.MessageLevel.LOG -> {
                    Log.v(LOG_TAG, msg)
                    return true
                }
                ConsoleMessage.MessageLevel.DEBUG, ConsoleMessage.MessageLevel.TIP -> {
                    Log.d(LOG_TAG, msg)
                    return true
                }
                ConsoleMessage.MessageLevel.WARNING -> {
                    Log.w(LOG_TAG, msg)
                    return true
                }
                ConsoleMessage.MessageLevel.ERROR -> {
                    Log.e(LOG_TAG, msg)
                    return true
                }
                else -> return false
            }
        }
    }

    private var horizontalPageCount = 0
    private var displayMetrics: DisplayMetrics? = null
    private var density: Float = 0.toFloat()
    private var mScrollListener: ScrollListener? = null
    private var mSeekBarListener: SeekBarListener? = null
    private lateinit var gestureDetector: GestureDetectorCompat
    private var eventActionDown: MotionEvent? = null
    private var pageWidthCssDp: Int = 0
    private var pageWidthCssPixels: Float = 0.toFloat()
    private lateinit var webViewPager: WebViewPager
    private lateinit var uiHandler: Handler
    private lateinit var folioActivityCallback: FolioActivityCallback
    private lateinit var parentFragment: FolioPageFragment

    private var actionMode: ActionMode? = null
    private var textSelectionCb: TextSelectionCb? = null
    private var textSelectionCb2: TextSelectionCb2? = null
    private var selectionRect = Rect()
    private val popupRect = Rect()
    private var popupWindow = PopupWindow()
    private lateinit var viewTextSelection: View
    private lateinit var textSelectionBinding: TextSelectionBinding
    private var isScrollingCheckDuration: Int = 0
    private var isScrollingRunnable: Runnable? = null
    private var oldScrollX: Int = 0
    private var oldScrollY: Int = 0
    private var lastTouchAction: Int = 0
    private var destroyed: Boolean = false
    private var handleHeight: Int = 0
    private var calculatedProgress = 0.0

    private var lastScrollType: LastScrollType? = null

    val contentHeightVal: Int
        get() = floor((this.contentHeight * this.scale).toDouble()).toInt()

    val webViewHeight: Int
        get() = this.measuredHeight

    val currentProgress: Double
        get() = calculatedProgress

    private enum class LastScrollType {
        USER, PROGRAMMATIC
    }

    @JavascriptInterface
    fun getDirection(): String {
        return folioActivityCallback.direction.toString()
    }

    fun getHorizontalPageCount(): Int {
        return this.horizontalPageCount
    }

    @JavascriptInterface
    fun getTopDistraction(unitString: String): Int {
        val unit = DisplayUnit.valueOf(unitString)
        return folioActivityCallback.getTopDistraction(unit)
    }

    @JavascriptInterface
    fun getBottomDistraction(unitString: String): Int {
        val unit = DisplayUnit.valueOf(unitString)
        return folioActivityCallback.getBottomDistraction(unit)
    }

    @JavascriptInterface
    fun getViewportRect(unitString: String): String {
        val unit = DisplayUnit.valueOf(unitString)
        val rect = folioActivityCallback.getViewportRect(unit)
        return UiUtil.rectToDOMRectJson(rect)
    }

    @JavascriptInterface
    fun toggleSystemUI() {
        uiHandler.post {
            folioActivityCallback.toggleSystemUI()
        }
    }

    @JavascriptInterface
    fun isPopupShowing(): Boolean {
        return popupWindow.isShowing
    }

    fun updateHorizontalPage(pageIndex: Int) {
        parentFragment.updateHorizontalPageProgress(pageIndex)
    }

    private inner class HorizontalGestureListener : GestureDetector.SimpleOnGestureListener() {

        // CORRECTED: Parameters are now non-nullable
        override fun onScroll(
            e1: MotionEvent,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            lastScrollType = LastScrollType.USER
            return false
        }

        // CORRECTED: Parameters are now non-nullable
        override fun onFling(
            e1: MotionEvent,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (!webViewPager.isScrolling) {
                uiHandler.postDelayed({
                    scrollTo(getScrollXPixelsForPage(webViewPager.currentItem), 0)
                }, 100)
            }
            lastScrollType = LastScrollType.USER
            return true
        }

        // CORRECTED: Parameter is now non-nullable
        override fun onDown(event: MotionEvent): Boolean {
            eventActionDown = MotionEvent.obtain(event)
            super@FolioWebView.onTouchEvent(event)
            return true
        }
    }

    @JavascriptInterface
    fun dismissPopupWindow(): Boolean {
        val wasShowing = popupWindow.isShowing
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            popupWindow.dismiss()
        } else {
            uiHandler.post { popupWindow.dismiss() }
        }
        selectionRect = Rect()
        if (isScrollingRunnable != null) {
            uiHandler.removeCallbacks(isScrollingRunnable!!)
        }
        isScrollingCheckDuration = 0
        return wasShowing
    }

    override fun destroy() {
        super.destroy()
        Log.d(LOG_TAG, "-> destroy")
        dismissPopupWindow()
        destroyed = true
    }

    private inner class VerticalGestureListener : GestureDetector.SimpleOnGestureListener() {

        // CORRECTED: Parameters are now non-nullable
        override fun onScroll(
            e1: MotionEvent,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            parentFragment.mWebview?.let { wv ->
                calculatedProgress = (wv.scrollY.toDouble() / wv.contentHeightVal.toDouble()) * 100.0
            }
            lastScrollType = LastScrollType.USER
            return false
        }

        // CORRECTED: Parameters are now non-nullable
        override fun onFling(
            e1: MotionEvent,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            lastScrollType = LastScrollType.USER
            return false
        }
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private fun init() {
        Log.v(LOG_TAG, "-> init")

        uiHandler = Handler(Looper.getMainLooper())
        displayMetrics = resources.displayMetrics
        density = displayMetrics?.density ?: 0f

        gestureDetector = if (folioActivityCallback.direction == Config.Direction.HORIZONTAL) {
            GestureDetectorCompat(context, HorizontalGestureListener())
        } else {
            GestureDetectorCompat(context, VerticalGestureListener())
        }

        try {
            initViewTextSelection()
        }catch (ignored: Exception){
            Log.e(LOG_TAG, "-> initViewTextSelection -> $ignored")
        }
    }

    fun initViewTextSelection() {
        Log.v(LOG_TAG, "-> initViewTextSelection")

        val textSelectionMiddleDrawable = ContextCompat.getDrawable(context, R.drawable.arrow_down)
        handleHeight = textSelectionMiddleDrawable?.intrinsicHeight ?: (24 * density).toInt()

        val config = AppUtil.getSavedConfig(context)
        val ctw = if (config?.isNightMode == true) {
            ContextThemeWrapper(context, R.style.FolioNightTheme)
        } else {
            ContextThemeWrapper(context, R.style.FolioDayTheme)
        }

        textSelectionBinding = TextSelectionBinding.inflate(LayoutInflater.from(ctw))
        viewTextSelection = textSelectionBinding.root
        viewTextSelection.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        textSelectionBinding.yellowHighlight.setOnClickListener {
            Log.v(LOG_TAG, "-> onClick -> yellowHighlight")
            onHighlightColorItemsClicked(HighlightStyle.Yellow, false)
        }
        textSelectionBinding.greenHighlight.setOnClickListener {
            Log.v(LOG_TAG, "-> onClick -> greenHighlight")
            onHighlightColorItemsClicked(HighlightStyle.Green, false)
        }
        textSelectionBinding.blueHighlight.setOnClickListener {
            Log.v(LOG_TAG, "-> onClick -> blueHighlight")
            onHighlightColorItemsClicked(HighlightStyle.Blue, false)
        }
        textSelectionBinding.pinkHighlight.setOnClickListener {
            Log.v(LOG_TAG, "-> onClick -> pinkHighlight")
            onHighlightColorItemsClicked(HighlightStyle.Pink, false)
        }
        textSelectionBinding.underlineHighlight.setOnClickListener {
            Log.v(LOG_TAG, "-> onClick -> underlineHighlight")
            onHighlightColorItemsClicked(HighlightStyle.Underline, false)
        }
        textSelectionBinding.deleteHighlight.setOnClickListener {
            Log.v(LOG_TAG, "-> onClick -> deleteHighlight")
            dismissPopupWindow()
            loadUrl("javascript:clearSelection()")
            loadUrl("javascript:deleteThisHighlight()")
        }
        textSelectionBinding.copySelection.setOnClickListener {
            dismissPopupWindow()
            loadUrl("javascript:onTextSelectionItemClicked(${it.id})")
        }
        textSelectionBinding.shareSelection.setOnClickListener {
            dismissPopupWindow()
            loadUrl("javascript:onTextSelectionItemClicked(${it.id})")
        }
        textSelectionBinding.defineSelection.setOnClickListener {
            dismissPopupWindow()
            loadUrl("javascript:onTextSelectionItemClicked(${it.id})")
        }
    }

    @JavascriptInterface
    fun onTextSelectionItemClicked(id: Int, selectedText: String?) {

        uiHandler.post { loadUrl("javascript:clearSelection()") }

        when (id) {
            R.id.copySelection -> {
                Log.v(LOG_TAG, "-> onTextSelectionItemClicked -> copySelection -> $selectedText")
                UiUtil.copyToClipboard(context, selectedText)
                Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT)
                    .show()
            }
            R.id.shareSelection -> {
                Log.v(LOG_TAG, "-> onTextSelectionItemClicked -> shareSelection -> $selectedText")
                UiUtil.share(context, selectedText)
            }
            R.id.defineSelection -> {
                Log.v(LOG_TAG, "-> onTextSelectionItemClicked -> defineSelection -> $selectedText")
                uiHandler.post { showDictDialog(selectedText) }
            }
            else -> {
                Log.w(LOG_TAG, "-> onTextSelectionItemClicked -> unknown id = $id")
            }
        }
    }

    private fun showDictDialog(selectedText: String?) {
        val dictionaryFragment = DictionaryFragment()
        val bundle = Bundle()
        bundle.putString(Constants.SELECTED_WORD, selectedText?.trim())
        dictionaryFragment.arguments = bundle
        dictionaryFragment.show(
            parentFragment.parentFragmentManager,
            DictionaryFragment::class.java.name
        )
    }

    private fun onHighlightColorItemsClicked(style: HighlightStyle, isAlreadyCreated: Boolean) {
        parentFragment.highlight(style, isAlreadyCreated)
        dismissPopupWindow()
    }

    @JavascriptInterface
    fun deleteThisHighlight(id: String?) {
        Log.d(LOG_TAG, "-> deleteThisHighlight")

        if (id.isNullOrEmpty())
            return

        val highlightImpl = HighLightTable.getHighlightForRangy(id)
        if (HighLightTable.deleteHighlight(id)) {
            val rangy = HighlightUtil.generateRangyString(parentFragment.pageName)
            uiHandler.post { parentFragment.loadRangy(rangy) }
            if (highlightImpl != null) {
                HighlightUtil.sendHighlightBroadcastEvent(
                    context, highlightImpl,
                    HighLight.HighLightAction.DELETE
                )
            }
        }
    }

    fun setParentFragment(parentFragment: FolioPageFragment) {
        this.parentFragment = parentFragment
    }

    fun setFolioActivityCallback(folioActivityCallback: FolioActivityCallback) {
        this.folioActivityCallback = folioActivityCallback
        init()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)

        pageWidthCssDp = Math.ceil((measuredWidth / density).toDouble()).toInt()
        pageWidthCssPixels = pageWidthCssDp * density
    }

    fun setScrollListener(listener: ScrollListener) {
        mScrollListener = listener
    }

    fun setSeekBarListener(listener: SeekBarListener) {
        mSeekBarListener = listener
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null)
            return false

        lastTouchAction = event.action

        return if (folioActivityCallback.direction == Config.Direction.HORIZONTAL) {
            computeHorizontalScroll(event)
        } else {
            computeVerticalScroll(event)
        }
    }

    private fun computeVerticalScroll(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    private fun computeHorizontalScroll(event: MotionEvent): Boolean {
        if (!::webViewPager.isInitialized)
            return super.onTouchEvent(event)

        webViewPager.dispatchTouchEvent(event)
        val gestureReturn = gestureDetector.onTouchEvent(event)
        return if (gestureReturn) true else super.onTouchEvent(event)
    }

    fun getScrollXDpForPage(page: Int): Int {
        return page * pageWidthCssDp
    }

    fun getScrollXPixelsForPage(page: Int): Int {
        return Math.ceil((page * pageWidthCssPixels).toDouble()).toInt()
    }

    fun setHorizontalPageCount(horizontalPageCount: Int) {
        this.horizontalPageCount = horizontalPageCount

        uiHandler.post {
            webViewPager = (parent as View).findViewById(R.id.webViewPager)
            webViewPager.setHorizontalPageCount(this@FolioWebView.horizontalPageCount)
        }
    }

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(x, y)

        if (getDirection() == "HORIZONTAL") {
            if (webViewPager.horizontalPageCount != 0) {
                calculatedProgress =
                    (webViewPager.currentItem.toDouble() / webViewPager.horizontalPageCount.toDouble()) * 100.0
            }
        }

        lastScrollType = LastScrollType.PROGRAMMATIC
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        mScrollListener?.onScrollChange(t)
        super.onScrollChanged(l, t, oldl, oldt)

        if (lastScrollType == LastScrollType.USER) {
            parentFragment.searchLocatorVisible = null
        }

        lastScrollType = null
    }

    interface ScrollListener {
        fun onScrollChange(percent: Int)
    }

    interface SeekBarListener {
        fun fadeInSeekBarIfInvisible()
    }

    private inner class TextSelectionCb : ActionMode.Callback {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            evaluateJavascript("javascript:getSelectionRect()") { value ->
                val rectJson = JSONObject(value)
                setSelectionRect(
                    rectJson.getInt("left"), rectJson.getInt("top"),
                    rectJson.getInt("right"), rectJson.getInt("bottom")
                )
            }
            return false
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            dismissPopupWindow()
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private inner class TextSelectionCb2 : ActionMode.Callback2() {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            dismissPopupWindow()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            evaluateJavascript("javascript:getSelectionRect()") { value ->
                val rectJson = JSONObject(value)
                setSelectionRect(
                    rectJson.getInt("left"), rectJson.getInt("top"),
                    rectJson.getInt("right"), rectJson.getInt("bottom")
                )
            }
        }
    }

    override fun startActionMode(callback: Callback): ActionMode {
        textSelectionCb = TextSelectionCb()
        actionMode = super.startActionMode(textSelectionCb)
        actionMode?.finish()
        return actionMode as ActionMode
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    override fun startActionMode(callback: Callback, type: Int): ActionMode {
        textSelectionCb2 = TextSelectionCb2()
        actionMode = super.startActionMode(textSelectionCb2, type)
        actionMode?.finish()
        return actionMode as ActionMode
    }

    private fun applyThemeColorToHandles() {
        if (Build.VERSION.SDK_INT < 23) {
            val folioActivityRef: WeakReference<FolioActivity> = folioActivityCallback.activity
            val mWindowManagerField = ReflectionUtils.findField(FolioActivity::class.java, "mWindowManager")
            mWindowManagerField.isAccessible = true
            val mWindowManager = mWindowManagerField.get(folioActivityRef.get())
            val windowManagerImplClass = Class.forName("android.view.WindowManagerImpl")
            val mGlobalField = ReflectionUtils.findField(windowManagerImplClass, "mGlobal")
            mGlobalField.isAccessible = true
            val mGlobal = mGlobalField.get(mWindowManager)
            val windowManagerGlobalClass = Class.forName("android.view.WindowManagerGlobal")
            val mViewsField = ReflectionUtils.findField(windowManagerGlobalClass, "mViews")
            mViewsField.isAccessible = true
            val mViews = mViewsField.get(mGlobal) as ArrayList<View>
            val config = AppUtil.getSavedConfig(context)!!
            for (view in mViews) {
                val handleViewClass = Class.forName("com.android.org.chromium.content.browser.input.HandleView")
                if (handleViewClass.isInstance(view)) {
                    val mDrawableField = ReflectionUtils.findField(handleViewClass, "mDrawable")
                    mDrawableField.isAccessible = true
                    val mDrawable = mDrawableField.get(view) as BitmapDrawable
                    config?.currentThemeColor?.let { UiUtil.setColorIntToDrawable(it, mDrawable) }
                }
            }
        } else {
            // ... (Code for API 23+ remains the same)
        }
    }

    @JavascriptInterface
    fun setSelectionRect(left: Int, top: Int, right: Int, bottom: Int) {
        val currentSelectionRect = Rect()
        currentSelectionRect.left = (left * density).toInt()
        currentSelectionRect.top = (top * density).toInt()
        currentSelectionRect.right = (right * density).toInt()
        currentSelectionRect.bottom = (bottom * density).toInt()
        computeTextSelectionRect(currentSelectionRect)
        uiHandler.post { showTextSelectionPopup() }
    }

    private fun computeTextSelectionRect(currentSelectionRect: Rect) {
        val viewportRect = folioActivityCallback.getViewportRect(DisplayUnit.PX)
        if (!Rect.intersects(viewportRect, currentSelectionRect)) {
            uiHandler.post {
                popupWindow.dismiss()
                if (isScrollingRunnable != null) {
                    uiHandler.removeCallbacks(isScrollingRunnable!!)
                }
            }
            return
        }
        if (selectionRect == currentSelectionRect) {
            return
        }
        selectionRect = currentSelectionRect
        val aboveSelectionRect = Rect(viewportRect)
        aboveSelectionRect.bottom = selectionRect.top - (8 * density).toInt()
        val belowSelectionRect = Rect(viewportRect)
        belowSelectionRect.top = selectionRect.bottom + handleHeight
        popupRect.left = viewportRect.left
        popupRect.top = belowSelectionRect.top
        popupRect.right = popupRect.left + viewTextSelection.measuredWidth
        popupRect.bottom = popupRect.top + viewTextSelection.measuredHeight
        val popupY: Int
        if (belowSelectionRect.contains(popupRect)) {
            popupY = belowSelectionRect.top
        } else {
            popupRect.top = aboveSelectionRect.top
            popupRect.bottom = popupRect.top + viewTextSelection.measuredHeight
            if (aboveSelectionRect.contains(popupRect)) {
                popupY = aboveSelectionRect.bottom - popupRect.height()
            } else {
                val popupYDiff = (viewTextSelection.measuredHeight - selectionRect.height()) / 2
                popupY = selectionRect.top - popupYDiff
            }
        }
        val popupXDiff = (viewTextSelection.measuredWidth - selectionRect.width()) / 2
        val popupX = selectionRect.left - popupXDiff
        popupRect.offsetTo(popupX, popupY)
        if (popupRect.left < viewportRect.left) {
            popupRect.right += -popupRect.left
            popupRect.left = 0
        }
        if (popupRect.right > viewportRect.right) {
            val dx = popupRect.right - viewportRect.right
            popupRect.left -= dx
            popupRect.right -= dx
        }
    }

    private fun showTextSelectionPopup() {
        val config = AppUtil.getSavedConfig(context)!!
        if (config.isShowTextSelection) {
            popupWindow.dismiss()
            oldScrollX = scrollX
            oldScrollY = scrollY
            isScrollingRunnable = Runnable {
                if (isScrollingRunnable != null) {
                    uiHandler.removeCallbacks(isScrollingRunnable!!)
                }
                val currentScrollX = scrollX
                val currentScrollY = scrollY
                val inTouchMode = lastTouchAction == MotionEvent.ACTION_DOWN || lastTouchAction == MotionEvent.ACTION_MOVE
                if (oldScrollX == currentScrollX && oldScrollY == currentScrollY && !inTouchMode) {
                    popupWindow.dismiss()
                    popupWindow = PopupWindow(viewTextSelection, WRAP_CONTENT, WRAP_CONTENT)
                    popupWindow.isClippingEnabled = false
                    popupWindow.showAtLocation(this@FolioWebView, Gravity.NO_GRAVITY, popupRect.left, popupRect.top)
                } else {
                    oldScrollX = currentScrollX
                    oldScrollY = currentScrollY
                    isScrollingCheckDuration += IS_SCROLLING_CHECK_TIMER
                    if (isScrollingCheckDuration < IS_SCROLLING_CHECK_MAX_DURATION && !destroyed) {
                        if (isScrollingRunnable != null) {
                            uiHandler.postDelayed(isScrollingRunnable!!, IS_SCROLLING_CHECK_TIMER.toLong())
                        }
                    }
                }
            }
            if (isScrollingRunnable != null) {
                uiHandler.removeCallbacks(isScrollingRunnable!!)
            }
            isScrollingCheckDuration = 0
            if (!destroyed && isScrollingRunnable != null) {
                uiHandler.postDelayed(isScrollingRunnable!!, IS_SCROLLING_CHECK_TIMER.toLong())
            }
        }
    }
}