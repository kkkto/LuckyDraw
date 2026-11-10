package com.example.luckydraw

import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment

/** One result layout for every grade; data comes from the completed SignalEngine session. */
class FortuneDialogFragment : DialogFragment() {
    private var announced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
        announced = savedInstanceState?.getBoolean(KEY_ANNOUNCED) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_ANNOUNCED, announced)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_fortune, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val score = requireArguments().getInt(ARG_SCORE)
        val (badge, fairy, color) = when {
            score >= 90 -> Triple(R.drawable.ld_badge_daegil, R.drawable.ld_fairy_daegil_v2, R.color.ld_grade_daegil)
            score >= 60 -> Triple(R.drawable.ld_badge_gil, R.drawable.ld_fairy_gil_v2, R.color.ld_grade_gil)
            score >= 40 -> Triple(R.drawable.ld_badge_sogil, R.drawable.ld_fairy_park_v2, R.color.ld_grade_sogil)
            else -> Triple(R.drawable.ld_badge_hyung, R.drawable.ld_fairy_hyung_v2, R.color.ld_grade_hyung)
        }
        view.findViewById<ImageView>(R.id.ivFortuneBadge).setImageResource(badge)
        view.findViewById<ImageView>(R.id.ivFortuneFairy).setImageResource(fairy)
        view.findViewById<TextView>(R.id.tvDialogGrade).apply {
            text = requireArguments().getString(ARG_GRADE).orEmpty()
            setTextColor(ContextCompat.getColor(requireContext(), R.color.ld_grade_on_color))
            val density = resources.displayMetrics.density
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(requireContext(), color))
                cornerRadius = 12 * density
            }
            setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
        }
        val rawMessage = requireArguments().getString(ARG_MESSAGE).orEmpty()
        view.findViewById<TextView>(R.id.tvDialogMessage).apply {
            text = SignalEngine.lockLineBreaksToPunctuation(rawMessage)
            post { fitClausesWithoutMidBreak(this, rawMessage) }
        }
        view.findViewById<TextView>(R.id.tvDialogScore).text =
            getString(R.string.fortune_score, score)
        view.findViewById<TextView>(R.id.tvDialogSummary).text =
            getString(R.string.fortune_summary, requireArguments().getInt(ARG_LONG_STOPS))
        view.findViewById<View>(R.id.btnFortuneClose).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btnFortuneConfirm).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btnFortuneVoice).setOnClickListener {
            (activity as? MainActivity)?.toggleFortuneVoice(speechText())
            refreshVoiceButton()
        }
        refreshVoiceButton()
    }

    fun refreshVoiceButton() {
        val host = activity as? MainActivity ?: return
        val (label, description) = when {
            !host.isFortuneVoiceEnabled() -> R.string.fortune_voice_off to R.string.cd_fortune_voice_off
            host.hasFortuneVoiceError() -> R.string.fortune_voice_unavailable to R.string.cd_fortune_voice_unavailable
            else -> R.string.fortune_voice_on to R.string.cd_fortune_voice_on
        }
        view?.findViewById<TextView>(R.id.btnFortuneVoice)?.apply {
            setText(label)
            contentDescription = getString(description)
        }
    }

    private fun speechText(): String {
        val grade = requireArguments().getString(ARG_GRADE).orEmpty().substringBefore('·').trim()
        val message = requireArguments().getString(ARG_MESSAGE).orEmpty()
            .replace('\u00A0', ' ')
            .replace("\u2060", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return "오늘의 운세는 ${grade}입니다. $message"
    }

    private fun fitClausesWithoutMidBreak(textView: TextView, rawMessage: String) {
        val availableWidth = (textView.width - textView.paddingLeft - textView.paddingRight).toFloat()
        if (availableWidth <= 0f) return
        val clauses = rawMessage
            .replace("\u00A0", " ")
            .replace("\u2060", "")
            .split(Regex("(?<=[,!.])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (clauses.isEmpty()) return
        val maxClauseWidth = clauses.maxOf { textView.paint.measureText(it) }
        if (maxClauseWidth > availableWidth) {
            val scale = (availableWidth / maxClauseWidth) * 0.97f
            val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, (textView.textSize * scale).coerceAtLeast(minPx))
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
        val widthDp = (resources.configuration.screenWidthDp - 32).coerceAtLeast(1).coerceAtMost(480)
        val heightDp = (resources.configuration.screenHeightDp - 24).coerceAtLeast(1).coerceAtMost(620)
        val density = resources.displayMetrics.density
        if (widthDp < 400 || heightDp < 500) {
            view?.findViewById<ImageView>(R.id.ivFortuneFairy)?.layoutParams?.apply {
                width = (96 * density).toInt()
                height = (96 * density).toInt()
            }
        }
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((widthDp * density).toInt(), (heightDp * density).toInt())
        }
        if (!announced) {
            announced = true
            (activity as? MainActivity)?.announceFortune(speechText())
        }
    }

    override fun onStop() {
        (activity as? MainActivity)?.cancelFortuneVoice()
        super.onStop()
    }

    companion object {
        const val TAG = "fortune_result"
        private const val ARG_SCORE = "score"
        private const val ARG_GRADE = "grade"
        private const val ARG_MESSAGE = "message"
        private const val ARG_LONG_STOPS = "long_stops"
        private const val KEY_ANNOUNCED = "announced"

        fun newInstance(score: Int, grade: String, message: String, longStops: Int): FortuneDialogFragment =
            FortuneDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SCORE, score)
                    putString(ARG_GRADE, grade)
                    putString(ARG_MESSAGE, message)
                    putInt(ARG_LONG_STOPS, longStops)
                }
            }
    }
}
