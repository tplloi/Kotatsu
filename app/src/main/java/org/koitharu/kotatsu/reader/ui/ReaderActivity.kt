package org.koitharu.kotatsu.reader.ui

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.*
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.domain.MangaIntent
import org.koitharu.kotatsu.base.ui.BaseFullscreenActivity
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.databinding.ActivityReaderBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.ui.pager.BaseReader
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.reader.ui.pager.reversed.ReversedReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.standard.PagerReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.webtoon.WebtoonReaderFragment
import org.koitharu.kotatsu.reader.ui.thumbnails.OnPageSelectListener
import org.koitharu.kotatsu.reader.ui.thumbnails.PagesThumbnailsSheet
import org.koitharu.kotatsu.utils.GridTouchHelper
import org.koitharu.kotatsu.utils.ScreenOrientationHelper
import org.koitharu.kotatsu.utils.ShareHelper
import org.koitharu.kotatsu.utils.ext.getDisplayMessage
import org.koitharu.kotatsu.utils.ext.hasGlobalPoint
import org.koitharu.kotatsu.utils.ext.observeWithPrevious

class ReaderActivity :
	BaseFullscreenActivity<ActivityReaderBinding>(),
	ChaptersBottomSheet.OnChapterChangeListener,
	GridTouchHelper.OnGridTouchListener,
	OnPageSelectListener,
	ReaderConfigDialog.Callback,
	ActivityResultCallback<Boolean>,
	ReaderControlDelegate.OnInteractionListener,
	OnApplyWindowInsetsListener {

	private val viewModel by viewModel<ReaderViewModel> {
		parametersOf(
			MangaIntent(intent),
			intent?.getParcelableExtra<ReaderState>(EXTRA_STATE),
			intent?.getStringExtra(EXTRA_BRANCH),
		)
	}

	private lateinit var touchHelper: GridTouchHelper
	private lateinit var orientationHelper: ScreenOrientationHelper
	private lateinit var controlDelegate: ReaderControlDelegate
	private val permissionsRequest = registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
		this
	)
	private var gestureInsets: Insets = Insets.NONE

	private val reader
		get() = supportFragmentManager.findFragmentById(R.id.container) as? BaseReader<*>

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityReaderBinding.inflate(layoutInflater))
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		touchHelper = GridTouchHelper(this, this)
		orientationHelper = ScreenOrientationHelper(this)
		controlDelegate = ReaderControlDelegate(lifecycleScope, get(), this)
		binding.toolbarBottom.inflateMenu(R.menu.opt_reader_bottom)
		binding.toolbarBottom.setOnMenuItemClickListener(::onOptionsItemSelected)
		insetsDelegate.interceptingWindowInsetsListener = this

		orientationHelper.observeAutoOrientation()
			.onEach {
				binding.toolbarBottom.menu.findItem(R.id.action_screen_rotate).isVisible = !it
			}.launchIn(lifecycleScope)

		viewModel.onError.observe(this, this::onError)
		viewModel.readerMode.observe(this, this::onInitReader)
		viewModel.onPageSaved.observe(this, this::onPageSaved)
		viewModel.uiState.observeWithPrevious(this, this::onUiStateChanged)
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.content.observe(this) {
			onLoadingStateChanged(viewModel.isLoading.value == true)
		}
		viewModel.isScreenshotsBlockEnabled.observe(this, this::setWindowSecure)
	}

	private fun onInitReader(mode: ReaderMode) {
		val currentReader = reader
		when (mode) {
			ReaderMode.WEBTOON -> if (currentReader !is WebtoonReaderFragment) {
				supportFragmentManager.commit {
					replace(R.id.container, WebtoonReaderFragment())
				}
			}
			ReaderMode.REVERSED -> if (currentReader !is ReversedReaderFragment) {
				supportFragmentManager.commit {
					replace(R.id.container, ReversedReaderFragment())
				}
			}
			ReaderMode.STANDARD -> if (currentReader !is PagerReaderFragment) {
				supportFragmentManager.commit {
					replace(R.id.container, PagerReaderFragment())
				}
			}
		}
		binding.toolbarBottom.menu.findItem(R.id.action_reader_mode).setIcon(
			when (mode) {
				ReaderMode.WEBTOON -> R.drawable.ic_script
				ReaderMode.REVERSED -> R.drawable.ic_read_reversed
				ReaderMode.STANDARD -> R.drawable.ic_book_page
			}
		)
		binding.appbarTop.postDelayed(1000) {
			setUiIsVisible(false)
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.opt_reader_top, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.action_reader_mode -> {
				ReaderConfigDialog.show(
					supportFragmentManager,
					when (reader) {
						is PagerReaderFragment -> ReaderMode.STANDARD
						is WebtoonReaderFragment -> ReaderMode.WEBTOON
						is ReversedReaderFragment -> ReaderMode.REVERSED
						else -> {
							showWaitWhileLoading()
							return false
						}
					}
				)
			}
			R.id.action_settings -> {
				startActivity(SimpleSettingsActivity.newReaderSettingsIntent(this))
			}
			R.id.action_chapters -> {
				ChaptersBottomSheet.show(
					supportFragmentManager,
					viewModel.manga?.chapters.orEmpty(),
					viewModel.getCurrentState()?.chapterId ?: 0L
				)
			}
			R.id.action_screen_rotate -> {
				orientationHelper.toggleOrientation()
			}
			R.id.action_pages_thumbs -> {
				val pages = viewModel.getCurrentChapterPages()
				if (!pages.isNullOrEmpty()) {
					PagesThumbnailsSheet.show(
						supportFragmentManager,
						pages,
						title?.toString().orEmpty(),
						reader?.getCurrentState()?.page ?: -1
					)
				} else {
					showWaitWhileLoading()
				}
			}
			R.id.action_save_page -> {
				if (!viewModel.content.value?.pages.isNullOrEmpty()) {
					if (ContextCompat.checkSelfPermission(
							this,
							Manifest.permission.WRITE_EXTERNAL_STORAGE
						) == PackageManager.PERMISSION_GRANTED
					) {
						onActivityResult(true)
					} else {
						permissionsRequest.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
					}
				} else {
					showWaitWhileLoading()
				}
			}
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	override fun onActivityResult(result: Boolean) {
		if (result) {
			viewModel.saveCurrentState(reader?.getCurrentState())
			viewModel.saveCurrentPage()
		}
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		val hasPages = !viewModel.content.value?.pages.isNullOrEmpty()
		binding.layoutLoading.isVisible = isLoading && !hasPages
		if (isLoading && hasPages) {
			binding.toastView.show(R.string.loading_, true)
		} else {
			binding.toastView.hide()
		}
	}

	private fun onError(e: Throwable) {
		val listener = ErrorDialogListener(e)
		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.error_occurred)
			.setMessage(e.getDisplayMessage(resources))
			.setNegativeButton(R.string.close, listener)
			.setOnCancelListener(listener)
		val resolveTextId = ExceptionResolver.getResolveStringId(e)
		if (resolveTextId != 0) {
			dialog.setPositiveButton(resolveTextId, listener)
		}
		dialog.show()
	}

	override fun onGridTouch(area: Int) {
		controlDelegate.onGridTouch(area, binding.container)
	}

	override fun onProcessTouch(rawX: Int, rawY: Int): Boolean {
		return if (
			rawX <= gestureInsets.left ||
			rawY <= gestureInsets.top ||
			rawX >= binding.root.width - gestureInsets.right ||
			rawY >= binding.root.height - gestureInsets.bottom ||
			binding.appbarTop.hasGlobalPoint(rawX, rawY) ||
			binding.appbarBottom?.hasGlobalPoint(rawX, rawY) == true
		) {
			false
		} else {
			val touchables = window.peekDecorView()?.touchables
			touchables?.none { it.hasGlobalPoint(rawX, rawY) } ?: true
		}
	}

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
		touchHelper.dispatchTouchEvent(ev)
		return super.dispatchTouchEvent(ev)
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		return controlDelegate.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
	}

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
		return controlDelegate.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
	}

	override fun onChapterChanged(chapter: MangaChapter) {
		viewModel.switchChapter(chapter.id)
	}

	override fun onPageSelected(page: MangaPage) {
		lifecycleScope.launch(Dispatchers.Default) {
			val pages = viewModel.content.value?.pages ?: return@launch
			val index = pages.indexOfFirst { it.id == page.id }
			if (index != -1) {
				withContext(Dispatchers.Main) {
					reader?.switchPageTo(index, true)
				}
			}
		}
	}

	override fun onReaderModeChanged(mode: ReaderMode) {
		viewModel.saveCurrentState(reader?.getCurrentState())
		viewModel.switchMode(mode)
	}

	private fun onPageSaved(uri: Uri?) {
		if (uri != null) {
			Snackbar.make(binding.container, R.string.page_saved, Snackbar.LENGTH_LONG)
				.setAnchorView(binding.appbarBottom)
				.setAction(R.string.share) {
					ShareHelper(this).shareImage(uri)
				}.show()
		} else {
			Snackbar.make(binding.container, R.string.error_occurred, Snackbar.LENGTH_SHORT)
				.setAnchorView(binding.appbarBottom)
				.show()
		}
	}

	private fun showWaitWhileLoading() {
		Toast.makeText(this, R.string.wait_for_loading_finish, Toast.LENGTH_SHORT).apply {
			setGravity(Gravity.CENTER, 0, 0)
		}.show()
	}

	private fun setWindowSecure(isSecure: Boolean) {
		if (isSecure) {
			window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		} else {
			window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
		}
	}

	private fun setUiIsVisible(isUiVisible: Boolean) {
		if (binding.appbarTop.isVisible != isUiVisible) {
			val transition = TransitionSet()
				.setOrdering(TransitionSet.ORDERING_TOGETHER)
				.addTransition(Slide(Gravity.TOP).addTarget(binding.appbarTop))
			binding.appbarBottom?.let { botomBar ->
				transition.addTransition(Slide(Gravity.BOTTOM).addTarget(botomBar))
			}
			TransitionManager.beginDelayedTransition(binding.root, transition)
			binding.appbarTop.isVisible = isUiVisible
			binding.appbarBottom?.isVisible = isUiVisible
			if (isUiVisible) {
				showSystemUI()
			} else {
				hideSystemUI()
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		gestureInsets = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
		val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		binding.appbarTop.updatePadding(
			top = systemBars.top,
			right = systemBars.right,
			left = systemBars.left
		)
		binding.appbarBottom?.updatePadding(
			bottom = systemBars.bottom,
			right = systemBars.right,
			left = systemBars.left
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onWindowInsetsChanged(insets: Insets) = Unit

	override fun switchPageBy(delta: Int) {
		reader?.switchPageBy(delta)
	}

	override fun toggleUiVisibility() {
		setUiIsVisible(!binding.appbarTop.isVisible)
	}

	private fun onUiStateChanged(uiState: ReaderUiState, previous: ReaderUiState?) {
		title = uiState.chapterName ?: uiState.mangaName ?: getString(R.string.loading_)
		supportActionBar?.subtitle = if (uiState.chapterNumber in 1..uiState.chaptersTotal) {
			getString(R.string.chapter_d_of_d, uiState.chapterNumber, uiState.chaptersTotal)
		} else {
			null
		}
		if (previous?.chapterName != null && uiState.chapterName != previous.chapterName) {
			if (!uiState.chapterName.isNullOrEmpty()) {
				binding.toastView.showTemporary(uiState.chapterName, TOAST_DURATION)
			}
		}
	}

	private inner class ErrorDialogListener(
		private val exception: Throwable,
	) : DialogInterface.OnClickListener, DialogInterface.OnCancelListener {

		override fun onClick(dialog: DialogInterface?, which: Int) {
			if (which == DialogInterface.BUTTON_POSITIVE) {
				dialog?.dismiss()
				tryResolve(exception)
			} else {
				onCancel(dialog)
			}
		}

		override fun onCancel(dialog: DialogInterface?) {
			if (viewModel.content.value?.pages.isNullOrEmpty()) {
				finishAfterTransition()
			}
		}

		private fun tryResolve(e: Throwable) {
			lifecycleScope.launch {
				if (exceptionResolver.resolve(e)) {
					viewModel.reload()
				} else {
					onCancel(null)
				}
			}
		}
	}

	companion object {

		const val ACTION_MANGA_READ = "${BuildConfig.APPLICATION_ID}.action.READ_MANGA"
		private const val EXTRA_STATE = "state"
		private const val EXTRA_BRANCH = "branch"
		private const val TOAST_DURATION = 1500L

		fun newIntent(context: Context, manga: Manga): Intent {
			return Intent(context, ReaderActivity::class.java)
				.putExtra(MangaIntent.KEY_MANGA, ParcelableManga(manga))
		}

		fun newIntent(context: Context, manga: Manga, branch: String?): Intent {
			return Intent(context, ReaderActivity::class.java)
				.putExtra(MangaIntent.KEY_MANGA, ParcelableManga(manga))
				.putExtra(EXTRA_BRANCH, branch)
		}

		fun newIntent(context: Context, manga: Manga, state: ReaderState?): Intent {
			return Intent(context, ReaderActivity::class.java)
				.putExtra(MangaIntent.KEY_MANGA, ParcelableManga(manga))
				.putExtra(EXTRA_STATE, state)
		}

		fun newIntent(context: Context, mangaId: Long): Intent {
			return Intent(context, ReaderActivity::class.java)
				.putExtra(MangaIntent.KEY_ID, mangaId)
		}
	}
}