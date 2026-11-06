/*
 * Copyright (c) 2026.
 *
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.fragments.other

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsSlidingMusicPanelActivity
import code.name.monkey.retromusic.databinding.FragmentFloatingMiniPlayerBinding
import code.name.monkey.retromusic.extensions.textColorPrimary
import code.name.monkey.retromusic.extensions.textColorSecondary
import code.name.monkey.retromusic.fragments.base.AbsMusicServiceFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.helper.PlayPauseButtonOnClickHandler
import code.name.monkey.retromusic.util.ArtistSeparator
import com.bumptech.glide.Glide
import kotlin.math.roundToInt

class FloatingMiniPlayerFragment : AbsMusicServiceFragment(R.layout.fragment_floating_mini_player),
    MusicProgressViewUpdateHelper.Callback {

    private var _binding: FragmentFloatingMiniPlayerBinding? = null
    private val binding get() = _binding!!
    private lateinit var progressViewUpdateHelper: MusicProgressViewUpdateHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentFloatingMiniPlayerBinding.bind(view)
        binding.actionPlayPause.setOnClickListener(PlayPauseButtonOnClickHandler())
        binding.actionNext.setOnClickListener { MusicPlayerRemote.playNextSong() }
        binding.actionDismiss.setOnClickListener { hideFloatingMiniPlayer() }
        binding.trackInfo.setOnClickListener {
            (activity as? AbsSlidingMusicPanelActivity)?.let {
                it.hideFloatingMiniPlayer()
                it.expandPanel()
            }
        }
        setupDragHandle()
        updateSong()
        updatePlayPauseButton()
    }

    override fun onServiceConnected() {
        updateSong()
        updatePlayPauseButton()
    }

    override fun onPlayingMetaChanged() {
        updateSong()
    }

    override fun onPlayStateChanged() {
        updatePlayPauseButton()
    }

    override fun onQueueChanged() {
        if (MusicPlayerRemote.playingQueue.isEmpty()) {
            hideFloatingMiniPlayer()
        } else {
            updateSong()
        }
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.progressBar.max = 1000
        binding.progressBar.progress =
            if (total > 0) (progress.toFloat() / total * 1000).roundToInt() else 0
    }

    override fun onResume() {
        super.onResume()
        progressViewUpdateHelper.start()
    }

    override fun onPause() {
        super.onPause()
        progressViewUpdateHelper.stop()
    }

    private fun updateSong() {
        val song = MusicPlayerRemote.currentSong
        binding.title.text = song.title
        binding.artist.text = ArtistSeparator.split(song.artistName).joinToString(", ")
        binding.title.setTextColor(textColorPrimary())
        binding.artist.setTextColor(textColorSecondary())
        binding.artwork.contentDescription = song.title
        Glide.with(this)
            .load(RetroGlideExtension.getSongModel(song))
            .transition(RetroGlideExtension.getDefaultTransition())
            .songCoverOptions(song)
            .into(binding.artwork)
    }

    private fun updatePlayPauseButton() {
        binding.actionPlayPause.setImageResource(
            if (MusicPlayerRemote.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    private fun hideFloatingMiniPlayer() {
        (activity as? AbsSlidingMusicPanelActivity)?.hideFloatingMiniPlayer()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragHandle() {
        var downX = 0f
        var downY = 0f
        var startTranslationX = 0f
        var startTranslationY = 0f

        binding.floatingPlayerDragHandle.setOnTouchListener { _, event ->
            val card = binding.floatingPlayerCard
            val container = binding.root
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTranslationX = card.translationX
                    startTranslationY = card.translationY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val edge = resources.getDimension(R.dimen.floating_mini_player_edge_margin)
                    val minX = edge - card.left
                    val maxX = container.width - edge - card.right
                    val minY = edge - card.top
                    val maxY = container.height - edge - card.bottom
                    card.translationX =
                        (startTranslationX + event.rawX - downX).coerceIn(minX, maxX)
                    card.translationY =
                        (startTranslationY + event.rawY - downY).coerceIn(minY, maxY)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    override fun onDestroyView() {
        Glide.with(binding.artwork).clear(binding.artwork)
        super.onDestroyView()
        _binding = null
    }
}