package com.folioreader.ui.fragment

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.webkit.*
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager.widget.ViewPager
import com.folioreader.Config
import com.folioreader.FolioReader
import com.folioreader.R
import com.folioreader.databinding.FolioPageFragmentBinding
import com.folioreader.mediaoverlay.MediaController
import com.folioreader.mediaoverlay.MediaControllerCallbacks
import com.folioreader.model.HighLight
import com.folioreader.model.HighlightImpl
import com.folioreader.model.event.*
import com.folioreader.model.locators.ReadLocator
import com.folioreader.model.locators.SearchLocator
import com.folioreader.model.sqlite.HighLightTable
import com.folioreader.ui.activity.FolioActivityCallback
import com.folioreader.ui.base.HtmlTask
import com.folioreader.ui.base.HtmlTaskCallback
import com.folioreader.ui.base.HtmlUtil
import com.folioreader.ui.view.FolioWebView
import com.folioreader.ui.view.LoadingView
import com.folioreader.ui.view.VerticalSeekbar
import com.folioreader.ui.view.WebViewPager
import com.folioreader.util.AppUtil
import com.folioreader.util.HighlightUtil
import com.folioreader.util.UiUtil
import com.folioreader.viewmodels.PageTrackerViewModel
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.readium.r2.shared.Link
import org.readium.r2.shared.Locations
import java.lang.Math.ceil
import java.util.*
import java.util.regex.Pattern
import kotlin.math.ceil

class FolioPageFragment : Fragment(),
    HtmlTaskCallback, MediaControllerCallbacks, FolioWebView.SeekBarListener {
    override fun onResume() {
        super.onResume()
        pageViewModel.setCurrentPage(webViewPager?.currentItem?.plus(1) ?: 0)
    }

    companion object {
        @JvmField
        val LOG_TAG: String = FolioPageFragment::class.java.simpleName
        private const val BUNDLE_SPINE_INDEX = "BUNDLE_SPINE_INDEX"
        private const val BUNDLE_BOOK_TITLE = "BUNDLE_BOOK_TITLE"
        private const val BUNDLE_SPINE_ITEM = "BUNDLE_SPINE_ITEM"
        private const val BUNDLE_READ_LOCATOR_CONFIG_CHANGE = "BUNDLE_READ_LOCATOR_CONFIG_CHANGE"
        const val BUNDLE_SEARCH_LOCATOR = "BUNDLE_SEARCH_LOCATOR"
        private const val BUNDLE_VIEW_MODEL = "BUNDLE_VIEW_MODEL"

        @JvmStatic
        fun newInstance(
            spineIndex: Int,
            bookTitle: String,
            spineRef: Link,
            bookId: String,
            viewModel: PageTrackerViewModel
        ): FolioPageFragment {
            val fragment = FolioPageFragment(viewModel)
            val args = Bundle()
            args.putInt(BUNDLE_SPINE_INDEX, spineIndex)
            args.putString(BUNDLE_BOOK_TITLE, bookTitle)
            args.putString(FolioReader.EXTRA_BOOK_ID, bookId)
            args.putSerializable(BUNDLE_SPINE_ITEM, spineRef)
            fragment.arguments = args
            return fragment
        }
    }

    private var _binding: FolioPageFragmentBinding? = null
    private val binding get() = _binding!!

    private lateinit var uiHandler: Handler
    private var mHtmlString: String? = null
    private var mAnchorId: String? = null
    private var rangy = ""
    private var highlightId: String? = null

    private var lastReadLocator: ReadLocator? = null
    private var outState: Bundle? = null
    private var savedInstanceState: Bundle? = null

    private var loadingView: LoadingView? = null
    private var mScrollSeekbar: VerticalSeekbar? = null
    var mWebview: FolioWebView? = null
    private var webViewPager: WebViewPager? = null
    private var mMinutesLeftTextView: TextView? = null
    private var currentPageIndicator: TextView? = null
    private var currentChapterIndicator: TextView? = null

    private var mActivityCallback: FolioActivityCallback? = null
    private var mTotalMinutes: Int = 0
    private var mFadeInAnimation: Animation? = null
    private var mFadeOutAnimation: Animation? = null

    lateinit var spineItem: Link
    private var spineIndex = -1
    private var mBookTitle: String? = null
    private var mIsPageReloaded: Boolean = false

    private var highlightStyle: String? = null
    private var mediaController: MediaController? = null
    private var mConfig: Config? = null
    private var mBookId: String? = null
    var searchLocatorVisible: SearchLocator? = null

    private lateinit var chapterUrl: Uri

    val pageName: String get() = mBookTitle + "$" + spineItem.href

    private val isCurrentFragment: Boolean
        get() {
            return isAdded && mActivityCallback?.currentChapterIndex == spineIndex
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        this.savedInstanceState = savedInstanceState
        uiHandler = Handler(Looper.getMainLooper())

        if (activity is FolioActivityCallback)
            mActivityCallback = activity as FolioActivityCallback?

        EventBus.getDefault().register(this)

        val args = requireArguments()
        spineIndex = args.getInt(BUNDLE_SPINE_INDEX)
        mBookTitle = args.getString(BUNDLE_BOOK_TITLE)
        spineItem = args.getSerializable(BUNDLE_SPINE_ITEM) as Link
        mBookId = args.getString(FolioReader.EXTRA_BOOK_ID)

        chapterUrl = Uri.parse(mActivityCallback?.streamerUrl + spineItem.href?.substring(1))

        // FIX: Use new getParcelable syntax
        searchLocatorVisible = savedInstanceState?.getParcelable(BUNDLE_SEARCH_LOCATOR, SearchLocator::class.java)

        mediaController = MediaController(activity, MediaController.MediaType.TTS, this)
        mediaController!!.setTextToSpeech(activity)

        highlightStyle = HighlightImpl.HighlightStyle.classForStyle(HighlightImpl.HighlightStyle.Normal)
        _binding = FolioPageFragmentBinding.inflate(inflater, container, false)

        mMinutesLeftTextView = binding.minutesLeft
        currentPageIndicator = binding.currentPage
        currentChapterIndicator = binding.currentChapter
        loadingView = binding.loadingView
        mConfig = AppUtil.getSavedConfig(context)

        setIndicatorVisibility()
        initSeekbar()
        initAnimations()
        initWebView()
        updatePagesLeftTextBg()

        return binding.root
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun pauseButtonClicked(event: MediaOverlayPlayPauseEvent) {
        if (isAdded && spineItem.href == event.href) {
            mediaController!!.stateChanged(event)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun speedChanged(event: MediaOverlaySpeedEvent) {
        mediaController?.setSpeed(event.speed)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun styleChanged(event: MediaOverlayHighlightStyleEvent) {
        if (isAdded) {
            when (event.style) {
                MediaOverlayHighlightStyleEvent.Style.DEFAULT -> highlightStyle =
                    HighlightImpl.HighlightStyle.classForStyle(HighlightImpl.HighlightStyle.Normal)

                MediaOverlayHighlightStyleEvent.Style.UNDERLINE -> highlightStyle =
                    HighlightImpl.HighlightStyle.classForStyle(HighlightImpl.HighlightStyle.DottetUnderline)

                MediaOverlayHighlightStyleEvent.Style.BACKGROUND -> highlightStyle =
                    HighlightImpl.HighlightStyle.classForStyle(HighlightImpl.HighlightStyle.TextColor)
                null -> TODO()
            }
            mWebview!!.loadUrl(String.format(getString(R.string.setmediaoverlaystyle), highlightStyle))
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun reload(reloadDataEvent: ReloadDataEvent) {
        if (isCurrentFragment)
            getLastReadLocator()

        if (isAdded) {
            mWebview!!.dismissPopupWindow()
            mWebview!!.initViewTextSelection()
            loadingView?.updateTheme()
            loadingView?.show()
            mIsPageReloaded = true
            setHtml(true)
            updatePagesLeftTextBg()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun updateHighlight(event: UpdateHighlightEvent) {
        if (isAdded) {
            this.rangy = HighlightUtil.generateRangyString(pageName)
            loadRangy(this.rangy)
        }
    }

    fun scrollToAnchorId(href: String) {
        if (!TextUtils.isEmpty(href) && href.indexOf('#') != -1) {
            mAnchorId = href.substring(href.lastIndexOf('#') + 1)
            if (loadingView != null && loadingView?.visibility != View.VISIBLE) {
                loadingView?.show()
                mWebview?.loadUrl(String.format(getString(R.string.go_to_anchor), mAnchorId))
                mAnchorId = null
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun resetCurrentIndex(resetIndex: RewindIndexEvent) {
        if (isCurrentFragment) {
            mWebview?.loadUrl("javascript:rewindCurrentIndex()")
        }
    }

    override fun onReceiveHtml(html: String) {
        if (isAdded) {
            mHtmlString = html
            setHtml(false)
        }
    }

    private fun setHtml(reloaded: Boolean) {
        mConfig = AppUtil.getSavedConfig(context)

        val href = spineItem.href
        var path = ""
        val forwardSlashLastIndex = href!!.lastIndexOf('/')
        if (forwardSlashLastIndex != -1) {
            path = href.substring(1, forwardSlashLastIndex + 1)
        }

        val mimeType: String = if (spineItem.typeLink!!.equals(getString(R.string.xhtml_mime_type), true)) {
            getString(R.string.xhtml_mime_type)
        } else {
            getString(R.string.html_mime_type)
        }

        uiHandler.post {
            mWebview!!.loadDataWithBaseURL(
                mActivityCallback?.streamerUrl + path,
                HtmlUtil.getHtmlContent(mWebview!!.context, mHtmlString, mConfig!!),
                mimeType,
                "UTF-8", null
            )
        }
    }

    fun scrollToLast() {
        val isPageLoading = loadingView == null || loadingView!!.visibility == View.VISIBLE
        Log.v(LOG_TAG, "-> scrollToLast -> isPageLoading = $isPageLoading")
        if (!isPageLoading) {
            loadingView?.show()
            mWebview?.loadUrl("javascript:scrollToLast()")
        }
    }

    fun scrollToFirst() {
        val isPageLoading = loadingView == null || loadingView!!.visibility == View.VISIBLE
        Log.v(LOG_TAG, "-> scrollToFirst -> isPageLoading = $isPageLoading")
        if (!isPageLoading) {
            loadingView?.show()
            mWebview?.loadUrl("javascript:scrollToFirst()")
        }
    }

    @SuppressLint("JavascriptInterface", "SetJavaScriptEnabled")
    private fun initWebView() {
        mWebview = binding.folioWebView
        mWebview!!.setParentFragment(this)
        webViewPager = binding.webViewPager

        if (activity is FolioActivityCallback)
            mWebview!!.setFolioActivityCallback((activity as FolioActivityCallback))

        setupScrollBar()
        mWebview!!.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val height = Math.floor((mWebview!!.contentHeight * mWebview!!.scale).toDouble()).toInt()
            val webViewHeight = mWebview!!.measuredHeight
            mScrollSeekbar?.maximum = height - webViewHeight
        }

        mWebview!!.settings.javaScriptEnabled = true
        mWebview!!.isVerticalScrollBarEnabled = false
        mWebview!!.settings.allowFileAccess = true
        mWebview!!.isHorizontalScrollBarEnabled = false
        mWebview!!.addJavascriptInterface(this, "Highlight")
        mWebview!!.addJavascriptInterface(this, "FolioPageFragment")
        mWebview!!.addJavascriptInterface(webViewPager!!, "WebViewPager")
        mWebview!!.addJavascriptInterface(loadingView!!, "LoadingView")
        mWebview!!.addJavascriptInterface(mWebview!!, "FolioWebView")

        mWebview?.setScrollListener(object : FolioWebView.ScrollListener {
            override fun onScrollChange(percent: Int) {
                setIndicatorVisibility()
                mScrollSeekbar?.setProgressAndThumb(percent)
                updateVerticalPageProgress(percent)
            }
        })

        mWebview!!.webViewClient = webViewClient
        mWebview!!.webChromeClient = webChromeClient
        mWebview!!.settings.defaultTextEncodingName = "utf-8"
        HtmlTask(this).execute(chapterUrl.toString())
    }

    private val webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            mWebview!!.loadUrl("javascript:checkCompatMode()")
            mWebview!!.loadUrl("javascript:alert(getReadingTime())")

            if (mActivityCallback?.direction == Config.Direction.HORIZONTAL)
                mWebview?.loadUrl("javascript:initHorizontalDirection()")

            view.loadUrl(String.format(getString(R.string.setmediaoverlaystyle), HighlightImpl.HighlightStyle.classForStyle(HighlightImpl.HighlightStyle.Normal)))

            val rangy = HighlightUtil.generateRangyString(pageName)
            this@FolioPageFragment.rangy = rangy
            if (rangy.isNotEmpty())
                loadRangy(rangy)

            if (mIsPageReloaded) {
                if (searchLocatorVisible != null) {
                    val callHighlightSearchLocator = String.format(getString(R.string.callHighlightSearchLocator), searchLocatorVisible?.locations?.cfi)
                    mWebview!!.loadUrl(callHighlightSearchLocator)
                } else if (isCurrentFragment) {
                    val cfi = lastReadLocator!!.locations.cfi
                    mWebview!!.loadUrl(String.format(getString(R.string.callScrollToCfi), cfi))
                } else {
                    if (spineIndex == mActivityCallback!!.currentChapterIndex - 1) {
                        mWebview!!.loadUrl("javascript:scrollToLast()")
                    } else {
                        loadingView?.hide()
                    }
                }
                mIsPageReloaded = false
            } else if (!TextUtils.isEmpty(mAnchorId)) {
                mWebview?.loadUrl(String.format(getString(R.string.go_to_anchor), mAnchorId))
                mAnchorId = null
            } else if (!TextUtils.isEmpty(highlightId)) {
                mWebview?.loadUrl(String.format(getString(R.string.go_to_highlight), highlightId))
                highlightId = null
            } else if (searchLocatorVisible != null) {
                val callHighlightSearchLocator = String.format(getString(R.string.callHighlightSearchLocator), searchLocatorVisible?.locations?.cfi)
                mWebview!!.loadUrl(callHighlightSearchLocator)
            } else if (isCurrentFragment) {
                val readLocator: ReadLocator? = if (savedInstanceState == null) {
                    Log.v(LOG_TAG, "-> onPageFinished -> took from getEntryReadLocator")
                    mActivityCallback!!.entryReadLocator
                } else {
                    Log.v(LOG_TAG, "-> onPageFinished -> took from bundle")
                    // FIX: Use new getParcelable syntax
                    savedInstanceState!!.getParcelable(BUNDLE_READ_LOCATOR_CONFIG_CHANGE, ReadLocator::class.java)
                        .also { savedInstanceState!!.remove(BUNDLE_READ_LOCATOR_CONFIG_CHANGE) }
                }

                if (readLocator != null) {
                    val cfi = readLocator.locations.cfi
                    Log.v(LOG_TAG, "-> onPageFinished -> readLocator -> $cfi")
                    mWebview!!.loadUrl(String.format(getString(R.string.callScrollToCfi), cfi))
                } else {
                    loadingView?.hide()
                }
            } else {
                if (spineIndex == mActivityCallback!!.currentChapterIndex - 1) {
                    mWebview!!.loadUrl("javascript:scrollToLast()")
                } else {
                    loadingView?.hide()
                }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true
            val urlOfEpub = mActivityCallback!!.goToChapter(url)
            if (!urlOfEpub) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!request.isForMainFrame && request.url.path != null && request.url.path!!.endsWith("/favicon.ico")) {
                try {
                    return WebResourceResponse("image/png", null, null)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "shouldInterceptRequest failed", e)
                }
            }
            return null
        }
    }

    private val webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
            val msg = cm.message() + " [" + cm.sourceId() + ":" + cm.lineNumber() + "]"
            return FolioWebView.onWebViewConsoleMessage(cm, "WebViewConsole", msg)
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            if (!this@FolioPageFragment.isAdded) return true

            if (TextUtils.isDigitsOnly(message)) {
                mTotalMinutes = try {
                    message.toInt()
                } catch (e: NumberFormatException) {
                    0
                }
            } else {
                val p = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                if (!p.matcher(message).matches() && message != "undefined" && isCurrentFragment) {
                    mediaController?.speakAudio(message)
                }
            }
            result.confirm()
            return true
        }
    }

    override fun onStop() {
        super.onStop()
        Log.v(LOG_TAG, "-> onStop -> ${spineItem.href} -> $isCurrentFragment")
        mediaController?.stop()
        if (isCurrentFragment)
            getLastReadLocator()
    }

    fun getLastReadLocator(): ReadLocator? {
        Log.v(LOG_TAG, "-> getLastReadLocator -> ${spineItem.href!!}")
        try {
            synchronized(this) {
                mWebview?.loadUrl(getString(R.string.callComputeLastReadCfi))
                (this as java.lang.Object).wait(5000)
            }
        } catch (e: InterruptedException) {
            Log.e(LOG_TAG, "-> ", e)
        }
        return lastReadLocator
    }

    @JavascriptInterface
    fun storeLastReadCfi(cfi: String) {
        synchronized(this) {
            val href = spineItem.href ?: ""
            val created = Date().time
            val locations = Locations()
            locations.cfi = cfi
            lastReadLocator = mBookId?.let { ReadLocator(it, href, created, locations) }

            val intent = Intent(FolioReader.ACTION_SAVE_READ_LOCATOR)
            intent.putExtra(FolioReader.EXTRA_READ_LOCATOR, lastReadLocator as Parcelable?)
            LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
            (this as java.lang.Object).notify()
        }
    }

    @JavascriptInterface
    fun setHorizontalPageCount(horizontalPageCount: Int) {
        Log.v(LOG_TAG, "-> setHorizontalPageCount = $horizontalPageCount -> ${spineItem.href}")
        mWebview!!.setHorizontalPageCount(horizontalPageCount)
    }

    fun loadRangy(rangy: String) {
        mWebview!!.loadUrl(String.format("javascript:if(typeof ssReader !== \"undefined\"){ssReader.setHighlights('%s');}", rangy))
    }

    private fun setupScrollBar() {
        mScrollSeekbar = binding.scrollSeekbar
        UiUtil.setColorIntToDrawable(mConfig!!.currentThemeColor, mScrollSeekbar!!.progressDrawable)
        val thumbDrawable = ContextCompat.getDrawable(requireActivity(), R.drawable.icons_sroll)
        UiUtil.setColorIntToDrawable(mConfig!!.currentThemeColor, thumbDrawable!!)
        mScrollSeekbar!!.thumb = thumbDrawable
    }

    private fun initSeekbar() {
        mScrollSeekbar = binding.scrollSeekbar
        mScrollSeekbar!!.progressDrawable.setColorFilter(
            ContextCompat.getColor(requireContext(), R.color.default_theme_accent_color),
            PorterDuff.Mode.SRC_IN
        )
    }

    private fun setIndicatorVisibility() {
        binding.indicatorLayout.visibility = if (mConfig?.isShowRemainingIndicator == true) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun updatePagesLeftTextBg() {
        val color = if (mConfig!!.isNightMode) Color.parseColor("#131313") else Color.WHITE
        binding.indicatorLayout.setBackgroundColor(color)
        binding.currentLocationIndicator.setBackgroundColor(color)
    }

    private fun updateVerticalPageProgress(scrollY: Int) {
        if (mConfig?.direction != Config.Direction.VERTICAL) return
        try {
            val currentChapter = mActivityCallback!!.currentChapterIndex + 1
            val currentPage = (ceil(scrollY.toDouble() / mWebview!!.webViewHeight) + 1).toInt()
            val totalPages = ceil(mWebview!!.contentHeightVal.toDouble() / mWebview!!.webViewHeight).toInt()
            val pagesRemaining = totalPages - currentPage
            val minutesRemaining = ceil((pagesRemaining * mTotalMinutes).toDouble() / totalPages).toInt()

            val minutesRemainingStr = when {
                minutesRemaining > 1 -> String.format(Locale.US, getString(R.string.minutes_left), minutesRemaining)
                minutesRemaining == 1 -> String.format(Locale.US, getString(R.string.minute_left), minutesRemaining)
                else -> getString(R.string.less_than_minute)
            }

            mMinutesLeftTextView?.text = minutesRemainingStr
            currentPageIndicator?.text = "Page $currentPage/$totalPages"
            currentChapterIndicator?.text = "Chapter $currentChapter"
        } catch (e: Exception) {
            Log.e("FolioPageFragment", "updateVerticalPageProgress failed", e)
        }
    }

    fun updateHorizontalPageProgress(currentPageIndex: Int) {
        if (mConfig?.direction != Config.Direction.HORIZONTAL) return
        try {
            val currentChapter = mActivityCallback!!.currentChapterIndex + 1
            val currentPage = currentPageIndex + 1
            val totalPages = webViewPager!!.horizontalPageCount
            val pagesRemaining = totalPages - currentPage
            val minutesRemaining = ceil((pagesRemaining * mTotalMinutes).toDouble() / totalPages).toInt()

            val minutesRemainingStr = when {
                minutesRemaining > 1 -> String.format(Locale.US, getString(R.string.minutes_left), minutesRemaining)
                minutesRemaining == 1 -> String.format(Locale.US, getString(R.string.minute_left), minutesRemaining)
                else -> getString(R.string.less_than_minute)
            }

            mMinutesLeftTextView?.text = minutesRemainingStr
            currentPageIndicator?.text = "Page $currentPage/$totalPages"
            currentChapterIndicator?.text = "Chapter $currentChapter"
        } catch (e: Exception) {
            Log.e("FolioPageFragment", "updateHorizontalPageProgress failed", e)
        }
    }

    private fun initAnimations() {
        mFadeInAnimation = AnimationUtils.loadAnimation(activity, R.anim.fadein)
        mFadeInAnimation?.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                mScrollSeekbar?.visibility = View.VISIBLE
            }
            override fun onAnimationEnd(animation: Animation) {
                fadeOutSeekBarIfVisible()
            }
            override fun onAnimationRepeat(animation: Animation) {}
        })
        mFadeOutAnimation = AnimationUtils.loadAnimation(activity, R.anim.fadeout)
        mFadeOutAnimation!!.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}
            override fun onAnimationEnd(animation: Animation) {
                mScrollSeekbar?.visibility = View.INVISIBLE
            }
            override fun onAnimationRepeat(animation: Animation) {}
        })
    }

    override fun fadeInSeekBarIfInvisible() {
        if (mScrollSeekbar?.visibility == View.INVISIBLE || mScrollSeekbar?.visibility == View.GONE) {
            mScrollSeekbar?.startAnimation(mFadeInAnimation)
        }
    }

    fun fadeOutSeekBarIfVisible() {
        if (mScrollSeekbar?.visibility == View.VISIBLE) {
            mScrollSeekbar?.startAnimation(mFadeOutAnimation)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mFadeInAnimation?.setAnimationListener(null)
        mFadeOutAnimation?.setAnimationListener(null)
        EventBus.getDefault().unregister(this)
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.v(LOG_TAG, "-> onSaveInstanceState -> ${spineItem.href}")
        this.outState = outState
        outState.putParcelable(BUNDLE_SEARCH_LOCATOR, searchLocatorVisible)
    }

    fun highlight(style: HighlightImpl.HighlightStyle, isAlreadyCreated: Boolean) {
        if (!isAlreadyCreated) {
            mWebview!!.loadUrl(String.format("javascript:if(typeof ssReader !== \"undefined\"){ssReader.highlightSelection('%s');}", HighlightImpl.HighlightStyle.classForStyle(style)))
        } else {
            mWebview!!.loadUrl(String.format("javascript:setHighlightStyle('%s')", HighlightImpl.HighlightStyle.classForStyle(style)))
        }
    }

    override fun resetCurrentIndex() {
        if (isCurrentFragment) {
            mWebview?.loadUrl("javascript:rewindCurrentIndex()")
        }
    }

    @JavascriptInterface
    fun onReceiveHighlights(html: String?) {
        if (html != null) {
            rangy = HighlightUtil.createHighlightRangy(requireActivity().applicationContext, html, mBookId, pageName, spineIndex, rangy)
        }
    }

    override fun highLightText(fragmentId: String) {
        mWebview?.loadUrl(String.format(getString(R.string.audio_mark_id), fragmentId))
    }

    override fun highLightTTS() {
        mWebview?.loadUrl("javascript:alert(getSentenceWithIndex('epub-media-overlay-playing'))")
    }

    @JavascriptInterface
    fun getUpdatedHighlightId(id: String?, style: String) {
        if (id != null) {
            val highlightImpl = HighLightTable.updateHighlightStyle(id, style)
            if (highlightImpl != null) {
                HighlightUtil.sendHighlightBroadcastEvent(requireActivity().applicationContext, highlightImpl, HighLight.HighLightAction.MODIFY)
            }
            val rangyString = HighlightUtil.generateRangyString(pageName)
            activity?.runOnUiThread { loadRangy(rangyString) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isCurrentFragment) {
            outState?.putSerializable(BUNDLE_READ_LOCATOR_CONFIG_CHANGE, lastReadLocator)
            if (activity != null && !requireActivity().isFinishing && lastReadLocator != null)
                mActivityCallback!!.storeLastReadLocator(lastReadLocator)
        }
        mWebview?.destroy()
    }

    override fun onError() {}

    fun scrollToHighlightId(highlightId: String) {
        this.highlightId = highlightId
        if (loadingView != null && loadingView!!.visibility != View.VISIBLE) {
            loadingView!!.show()
            mWebview!!.loadUrl(String.format(getString(R.string.go_to_highlight), highlightId))
            this.highlightId = null
        }
    }

    fun scrollToCFI(cfi: String) {
        if (loadingView != null && loadingView?.visibility != View.VISIBLE) {
            loadingView?.show()
            mWebview?.loadUrl(String.format(getString(R.string.callScrollToCfi), cfi))
        }
    }

    fun highlightSearchLocator(searchLocator: SearchLocator) {
        Log.v(LOG_TAG, "-> highlightSearchLocator")
        this.searchLocatorVisible = searchLocator
        if (loadingView != null && loadingView!!.visibility != View.VISIBLE) {
            loadingView!!.show()
            val callHighlightSearchLocator = String.format(getString(R.string.callHighlightSearchLocator), searchLocatorVisible?.locations?.cfi)
            mWebview!!.loadUrl(callHighlightSearchLocator)
        }
    }

    fun clearSearchLocator() {
        Log.v(LOG_TAG, "-> clearSearchLocator -> ${spineItem.href!!}")
        mWebview!!.loadUrl(getString(R.string.callClearSelection))
        searchLocatorVisible = null
    }
}