package com.folioreader.ui.view

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.folioreader.Config
import com.folioreader.R
import com.folioreader.databinding.ViewConfigBinding // Import the binding class
import com.folioreader.model.event.ReloadDataEvent
import com.folioreader.ui.activity.FolioActivity
import com.folioreader.ui.activity.FolioActivityCallback
import com.folioreader.ui.adapter.FontAdapter
import com.folioreader.ui.fragment.MediaControllerFragment
import com.folioreader.util.AppUtil
import com.folioreader.util.UiUtil
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ConfigBottomSheetDialogFragment : BottomSheetDialogFragment() {

    companion object {
        const val FADE_DAY_NIGHT_MODE = 10
        @JvmField
        val LOG_TAG: String = ConfigBottomSheetDialogFragment::class.java.simpleName
    }

    // ViewBinding properties
    private var _binding: ViewConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: Config
    private var isNightMode = false
    private lateinit var activityCallback: FolioActivityCallback
    private var backgroundShapeDrawable: MaterialShapeDrawable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout using ViewBinding
        _binding = ViewConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (activity is FolioActivity)
            activityCallback = activity as FolioActivity

        config = AppUtil.getSavedConfig(requireActivity())!!
        initViews()
        initBackgroundShape()
        updateContainerBackgroundColor(isNightMode)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up the binding reference
        _binding = null
    }

    private fun initBackgroundShape() {
    
        val shapeAppearanceModel = ShapeAppearanceModel.Builder().build()

        backgroundShapeDrawable = MaterialShapeDrawable(shapeAppearanceModel).apply {
            fillColor = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), if (isNightMode) R.color.night else R.color.white)
            )
        }
        binding.container.background = backgroundShapeDrawable
    }

    private fun updateContainerBackgroundColor(isNightMode: Boolean) {
        backgroundShapeDrawable?.fillColor = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), if (isNightMode) R.color.night else R.color.white)
        )
        binding.container.background = backgroundShapeDrawable
    }

    private fun initViews() {
        inflateView()
        configFonts()
        binding.viewConfigFontSize.text = config.fontSize.toString()
        configFontSizeButtons()
        selectFont(config.font)
        isNightMode = config.isNightMode

        if (isNightMode) {
            updateContainerBackgroundColor(true)
            binding.viewConfigFontSize.setTextColor(ContextCompat.getColor(requireContext(), R.color.lightText))
            binding.viewConfigFontSizeBtnIncrease.background = ContextCompat.getDrawable(requireContext(), R.drawable.buttons_night_rounded_corner_background)
            binding.viewConfigFontSizeBtnDecrease.background = ContextCompat.getDrawable(requireContext(), R.drawable.buttons_night_rounded_corner_background)
            UiUtil.setColorResToDrawable(R.color.lightText, binding.viewConfigFontSizeBtnIncrease.drawable)
            UiUtil.setColorResToDrawable(R.color.lightText, binding.viewConfigFontSizeBtnDecrease.drawable)
            binding.viewConfigFontType.background = ContextCompat.getDrawable(requireContext(), R.drawable.buttons_night_rounded_corner_background)
        } else {
            updateContainerBackgroundColor(false)
            binding.viewConfigFontSize.setTextColor(ContextCompat.getColor(requireContext(), R.color.night))
            binding.viewConfigFontSizeBtnIncrease.background = ContextCompat.getDrawable(requireContext(), R.drawable.buttons_day_rounded_corner_background)
            binding.viewConfigFontSizeBtnDecrease.background = ContextCompat.getDrawable(requireContext(), R.drawable.buttons_day_rounded_corner_background)
            UiUtil.setColorResToDrawable(R.color.night, binding.viewConfigFontSizeBtnIncrease.drawable)
            UiUtil.setColorResToDrawable(R.color.night, binding.viewConfigFontSizeBtnDecrease.drawable)
            binding.viewConfigFontType.background = ContextCompat.getDrawable(requireContext(), R.drawable.buttons_day_rounded_corner_background)
        }

        if (isNightMode) {
            binding.viewConfigIbDayMode.isSelected = false
            binding.viewConfigIbNightMode.isSelected = true
        } else {
            binding.viewConfigIbDayMode.isSelected = true
            binding.viewConfigIbNightMode.isSelected = false
        }
    }

    @SuppressLint("ResourceAsColor")
    private fun inflateView() {
        if (config.allowedDirection != Config.AllowedDirection.VERTICAL_AND_HORIZONTAL) {
            binding.buttonVertical.visibility = View.GONE
            binding.buttonHorizontal.visibility = View.GONE
        }

        binding.viewConfigIbDayMode.setOnClickListener {
            isNightMode = true
            toggleBlackTheme()
            binding.viewConfigIbDayMode.isSelected = true
            binding.viewConfigIbNightMode.isSelected = false
            setToolBarColor()
            setAudioPlayerBackground()
            dialog?.hide()
        }

        binding.viewConfigIbNightMode.setOnClickListener {
            isNightMode = false
            toggleBlackTheme()
            binding.viewConfigIbDayMode.isSelected = false
            binding.viewConfigIbNightMode.isSelected = true
            setToolBarColor()
            setAudioPlayerBackground()
            dialog?.hide()
        }

        if (activityCallback.direction == Config.Direction.HORIZONTAL) {
            binding.buttonHorizontal.isSelected = true
        } else if (activityCallback.direction == Config.Direction.VERTICAL) {
            binding.buttonVertical.isSelected = true
        }

        binding.buttonVertical.setOnClickListener {
            config = AppUtil.getSavedConfig(requireContext())!!
            config.direction = Config.Direction.VERTICAL
            AppUtil.saveConfig(requireContext(), config)
            activityCallback.onDirectionChange(Config.Direction.VERTICAL)
            binding.buttonHorizontal.isSelected = false
            binding.buttonVertical.isSelected = true
        }

        binding.buttonHorizontal.setOnClickListener {
            config = AppUtil.getSavedConfig(requireContext())!!
            config.direction = Config.Direction.HORIZONTAL
            AppUtil.saveConfig(requireContext(), config)
            activityCallback.onDirectionChange(Config.Direction.HORIZONTAL)
            binding.buttonHorizontal.isSelected = true
            binding.buttonVertical.isSelected = false
        }
    }

    private var fontChanged = false

    @SuppressLint("ResourceAsColor")
    private fun configFonts() {
        val colorStateList = UiUtil.getColorList(
            config.currentThemeColor,
            ContextCompat.getColor(requireContext(), R.color.grey_color)
        )

        binding.buttonVertical.setTextColor(colorStateList)
        binding.buttonHorizontal.setTextColor(colorStateList)

        val adapter = FontAdapter(config, requireContext())
        binding.viewConfigFontSpinner.adapter = adapter
        binding.viewConfigFontSpinner.background.setColorFilter(
            ContextCompat.getColor(requireContext(), if (config.isNightMode) R.color.night_default_font_color else R.color.day_default_font_color),
            PorterDuff.Mode.SRC_ATOP
        )

        val fontIndex = adapter.fontKeyList.indexOf(config.font)
        binding.viewConfigFontSpinner.setSelection(if (fontIndex < 0) 0 else fontIndex)

        binding.viewConfigFontSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedFont = adapter.fontKeyList[position]
                selectFont(selectedFont)
                fontChanged = true
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun selectFont(selectedFont: String) {
        config.font = selectedFont
        AppUtil.saveConfig(activity, config)
        if (fontChanged) {
            EventBus.getDefault().post(ReloadDataEvent())
            fontChanged = false
        }
    }

    private fun toggleBlackTheme() {
        val day = ContextCompat.getColor(requireContext(), R.color.white)
        val night = ContextCompat.getColor(requireContext(), R.color.night)
        val colorAnimation = ValueAnimator.ofObject(
            ArgbEvaluator(),
            if (isNightMode) night else day, if (isNightMode) day else night
        )
        colorAnimation.duration = FADE_DAY_NIGHT_MODE.toLong()

        colorAnimation.addUpdateListener { animator ->
            val value = animator.animatedValue as Int
            backgroundShapeDrawable?.fillColor = ColorStateList.valueOf(value)
            binding.container.background = backgroundShapeDrawable
        }

        colorAnimation.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animator: Animator) {}
            override fun onAnimationEnd(animator: Animator) {
                isNightMode = !isNightMode
                config.isNightMode = isNightMode
                AppUtil.saveConfig(activity, config)
                EventBus.getDefault().post(ReloadDataEvent())
            }
            override fun onAnimationCancel(animator: Animator) {}
            override fun onAnimationRepeat(animator: Animator) {}
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val attrs = intArrayOf(android.R.attr.navigationBarColor)
            val typedArray = activity?.theme?.obtainStyledAttributes(attrs)
            val defaultNavigationBarColor = typedArray?.getColor(
                0,
                ContextCompat.getColor(requireContext(), R.color.white)
            )
            val black = ContextCompat.getColor(requireContext(), R.color.black)

            val navigationColorAnim = ValueAnimator.ofObject(
                ArgbEvaluator(),
                if (isNightMode) black else defaultNavigationBarColor,
                if (isNightMode) defaultNavigationBarColor else black
            )

            navigationColorAnim.addUpdateListener { valueAnimator ->
                val value = valueAnimator.animatedValue as Int
                activity?.window?.navigationBarColor = value
            }

            navigationColorAnim.duration = FADE_DAY_NIGHT_MODE.toLong()
            navigationColorAnim.start()
        }

        colorAnimation.start()
    }

    private var debounceFuture: ScheduledFuture<*>? = null
    private val debounceExecutor = Executors.newSingleThreadScheduledExecutor()

    private fun configFontSizeButtons() {
        binding.viewConfigFontSizeBtnDecrease.setOnClickListener {
            if (config.fontSize > 1) {
                config.fontSize -= 1
                binding.viewConfigFontSize.text = config.fontSize.toString()
                debounce {
                    AppUtil.saveConfig(activity, config)
                    EventBus.getDefault().post(ReloadDataEvent())
                }
            }
        }
        binding.viewConfigFontSizeBtnIncrease.setOnClickListener {
            if (config.fontSize < 10) {
                config.fontSize += 1
                binding.viewConfigFontSize.text = config.fontSize.toString()
                debounce {
                    AppUtil.saveConfig(activity, config)
                    EventBus.getDefault().post(ReloadDataEvent())
                }
            }
        }
    }

    private fun debounce(delayMillis: Long = 300, action: () -> Unit) {
        debounceFuture?.cancel(false)
        debounceFuture = debounceExecutor.schedule({
            action()
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun setToolBarColor() {
        if (isNightMode) {
            activityCallback.setDayMode()
        } else {
            activityCallback.setNightMode()
        }
    }

    private fun setAudioPlayerBackground() {
        val mediaControllerFragment: MediaControllerFragment? =
            parentFragmentManager.findFragmentByTag(MediaControllerFragment.LOG_TAG) as? MediaControllerFragment

        mediaControllerFragment?.let {
            if (isNightMode) {
                it.setDayMode()
            } else {
                it.setNightMode()
            }
        }
    }
}