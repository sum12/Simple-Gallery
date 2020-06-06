package com.simplemobiletools.gallery.pro.activities

import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.CreateNewFolderDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.commons.views.MyGridLayoutManager
import com.simplemobiletools.commons.views.MyRecyclerView
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.adapters.MediaAdapter
import com.simplemobiletools.gallery.pro.asynctasks.GetMediaAsynctask
import com.simplemobiletools.gallery.pro.databases.GalleryDatabase
import com.simplemobiletools.gallery.pro.dialogs.*
import com.simplemobiletools.gallery.pro.extensions.*
import com.simplemobiletools.gallery.pro.helpers.*
import com.simplemobiletools.gallery.pro.interfaces.DirectoryDao
import com.simplemobiletools.gallery.pro.interfaces.MediaOperationsListener
import com.simplemobiletools.gallery.pro.interfaces.MediumDao
import com.simplemobiletools.gallery.pro.interfaces.ServerDao
import com.simplemobiletools.gallery.pro.models.Medium
import com.simplemobiletools.gallery.pro.models.ThumbnailItem
import com.simplemobiletools.gallery.pro.models.ThumbnailSection
import kotlinx.android.synthetic.main.activity_media.*
import java.io.File
import java.util.*
import java.util.logging.Logger

class BackgroundActivity : SimpleActivity(), MediaOperationsListener {
    private var mIsUploadIntent: Boolean = false
    private var mIsDownloadCachedIntent: Boolean = false
    private var mIsDownloadIntent: Boolean =  false
    private var mBackgroundType: String = ""
    private val LAST_MEDIA_CHECK_PERIOD = 3000L

    private var mPath = ""
    private var mIsGetImageIntent = false
    private var mIsGetVideoIntent = false
    private var mIsGetAnyIntent = false
    private var mIsGettingMedia = false
    private var mAllowPickingMultiple = false
    private var mShowAll = false
    private var mLoadedInitialPhotos = true
    private var mIsSearchOpen = false
    private var mLatestMediaId = 0L
    private var mLatestMediaDateId = 0L
    private var mLastMediaHandler = Handler()
    private var mTempShowHiddenHandler = Handler()
    private var mCurrAsyncTask: GetMediaAsynctask? = null
    private var mZoomListener: MyRecyclerView.MyZoomListener? = null
    private var mSearchMenuItem: MenuItem? = null

    private var mStoredAnimateGifs = true
    private var mStoredCropThumbnails = true
    private var mStoredScrollHorizontally = true
    private var mStoredShowInfoBubble = true
    private var mStoredShowFileTypes = true
    private var mStoredTextColor = 0
    private var mStoredPrimaryColor = 0

    private lateinit var mMediumDao: MediumDao
    private lateinit var mDirectoryDao: DirectoryDao
    private lateinit var mServerDao: ServerDao

    companion object {
        var mMedia = ArrayList<ThumbnailItem>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)

        mServerDao = ServerDao(this)

        intent.apply {
            mIsGetImageIntent = getBooleanExtra(GET_IMAGE_INTENT, false)
            mIsGetVideoIntent = getBooleanExtra(GET_VIDEO_INTENT, false)
            mIsGetAnyIntent = getBooleanExtra(GET_ANY_INTENT, false)
            mAllowPickingMultiple = getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            mIsDownloadIntent = getBooleanExtra(GET_DOWNLOAD_INTENT,  false)
            mIsDownloadCachedIntent = getBooleanExtra(GET_DOWNLOADCACHED_INTENT,  false)
            mIsUploadIntent = getBooleanExtra(GET_UPLOAD_INTENT,  false)
        }

        media_refresh_layout.setOnRefreshListener { getMedia() }
        try {
            mBackgroundType = intent.getStringExtra(GET_BACKGROUND_INTENT)
        } catch (e: Exception) {
            showErrorToast(e)
            finish()
            return
        }

        storeStateVariables()

        if (mShowAll) {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            registerFileUpdateListener()
        }

        updateWidgets()
    }

    override fun onStart() {
        super.onStart()
        mTempShowHiddenHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        if (mStoredAnimateGifs != config.animateGifs) {
            getMediaAdapter()?.updateAnimateGifs(config.animateGifs)
        }

        if (mStoredCropThumbnails != config.cropThumbnails) {
            getMediaAdapter()?.updateCropThumbnails(config.cropThumbnails)
        }

        if (mStoredScrollHorizontally != config.scrollHorizontally) {
            mLoadedInitialPhotos = false
            media_grid.adapter = null
            getMedia()
        }

        if (mStoredShowFileTypes != config.showThumbnailFileTypes) {
            getMediaAdapter()?.updateShowFileTypes(config.showThumbnailFileTypes)
        }

        if (mStoredTextColor != config.textColor) {
            getMediaAdapter()?.updateTextColor(config.textColor)
        }

        if (mStoredPrimaryColor != config.primaryColor) {
            getMediaAdapter()?.updatePrimaryColor(config.primaryColor)
            media_horizontal_fastscroller.updatePrimaryColor()
            media_vertical_fastscroller.updatePrimaryColor()
        }

        media_horizontal_fastscroller.updateBubbleColors()
        media_vertical_fastscroller.updateBubbleColors()
        media_horizontal_fastscroller.allowBubbleDisplay = false//config.showInfoBubble
        media_vertical_fastscroller.allowBubbleDisplay = false //config.showInfoBubble
        media_refresh_layout.isEnabled = true //config.enablePullToRefresh
        invalidateOptionsMenu()

        Logger.getLogger(BackgroundActivity::class.java.name).info("mPath is $mPath")

        if (mMedia.isEmpty() || config.getFolderSorting(mPath) and SORT_BY_RANDOM == 0) {
            if (shouldSkipAuthentication()) {
                tryLoadGallery()
            } else {
                finish()

            }
        }
    }

    private fun tryLoadGallery() {
        handlePermission(PERMISSION_WRITE_STORAGE) {
            if (it) {
                val titlebar = when (mBackgroundType) {
                    UPLOAD -> getString(R.string.upload_title)
                    DOWNLOAD -> getString(R.string.donwload_title)
                    DOWNLOAD_CACHED -> getString(R.string.donwload_cache_title)
                    else -> "NA"
                }
                updateActionBarTitle(titlebar)
                getMedia()
                setupLayoutManager()
            } else {
                toast(R.string.no_storage_permissions)
                finish()
            }
        }
    }

    private fun shouldSkipAuthentication() = intent.getBooleanExtra(SKIP_AUTHENTICATION, true)

    override fun onPause() {
        super.onPause()
        mIsGettingMedia = false
        media_refresh_layout.isRefreshing = false
        storeStateVariables()
        mLastMediaHandler.removeCallbacksAndMessages(null)

        if (!mMedia.isEmpty()) {
            mCurrAsyncTask?.stopFetching()
        }
    }

    override fun onStop() {
        super.onStop()
        mSearchMenuItem?.collapseActionView()

        if (config.temporarilyShowHidden || config.tempSkipDeleteConfirmation) {
            mTempShowHiddenHandler.postDelayed({
                config.temporarilyShowHidden = false
                config.tempSkipDeleteConfirmation = false
            }, SHOW_TEMP_HIDDEN_DURATION)
        } else {
            mTempShowHiddenHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (config.showAll && !isChangingConfigurations) {
            config.temporarilyShowHidden = false
            config.tempSkipDeleteConfirmation = false
            unregisterFileUpdateListener()
            GalleryDatabase.destroyInstance()
        }

        mTempShowHiddenHandler.removeCallbacksAndMessages(null)
        mMedia.clear()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_media, menu)

        val isFolderHidden = mPath.containsNoMedia()
        menu.apply {
            findItem(R.id.group).isVisible = !config.scrollHorizontally


            findItem(R.id.empty_recycle_bin).isVisible = false //mPath == RECYCLE_BIN
            findItem(R.id.empty_disable_recycle_bin).isVisible = false //mPath == RECYCLE_BIN
            findItem(R.id.restore_all_files).isVisible = false //mPath == RECYCLE_BIN

            findItem(R.id.folder_view).isVisible = false //mShowAll
            findItem(R.id.open_camera).isVisible = false //mShowAll
            findItem(R.id.about).isVisible = false //mShowAll
            findItem(R.id.create_new_folder).isVisible = false //!mShowAll && mPath != RECYCLE_BIN && mPath != FAVORITES

            findItem(R.id.temporarily_show_hidden).isVisible = false //!config.shouldShowHidden
            findItem(R.id.stop_showing_hidden).isVisible = false //config.temporarilyShowHidden

            val viewType = VIEW_TYPE_LIST //config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
            findItem(R.id.increase_column_count).isVisible = false //viewType == VIEW_TYPE_GRID && config.mediaColumnCnt < MAX_COLUMN_COUNT
            findItem(R.id.reduce_column_count).isVisible = false //viewType == VIEW_TYPE_GRID && config.mediaColumnCnt > 1
            findItem(R.id.toggle_filename).isVisible = false //viewType == VIEW_TYPE_GRID
        }

        setupSearch(menu)
        updateMenuItemColors(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort -> showSortingDialog()
            //R.id.filter -> showFilterMediaDialog()
            //R.id.empty_recycle_bin -> emptyRecycleBin()
            //R.id.empty_disable_recycle_bin -> emptyAndDisableRecycleBin()
            //R.id.restore_all_files -> restoreAllFiles()
            //R.id.toggle_filename -> toggleFilenameVisibility()
            //R.id.open_camera -> launchCamera()
            //R.id.folder_view -> switchToFolderView()
            //R.id.change_view_type -> changeViewType()
            R.id.group -> showGroupByDialog()
            //R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
            //R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
            //R.id.increase_column_count -> increaseColumnCount()
            //R.id.reduce_column_count -> reduceColumnCount()
            //R.id.slideshow -> startSlideshow()
            R.id.settings -> launchSettings()
            R.id.about -> launchAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun startSlideshow() {
        if (mMedia.isNotEmpty()) {
            Intent(this, ViewPagerActivity::class.java).apply {
                val item = mMedia.firstOrNull { it is Medium } as? Medium
                        ?: return
                putExtra(PATH, item.path)
                putExtra(SHOW_ALL, mShowAll)
                putExtra(SLIDESHOW_START_ON_ENTER, true)
                startActivity(this)
            }
        }
    }

    private fun storeStateVariables() {
        config.apply {
            mStoredAnimateGifs = animateGifs
            mStoredCropThumbnails = cropThumbnails
            mStoredScrollHorizontally = scrollHorizontally
            mStoredShowInfoBubble = showInfoBubble
            mStoredShowFileTypes = showThumbnailFileTypes
            mStoredTextColor = textColor
            mStoredPrimaryColor = primaryColor
            mShowAll = showAll
        }
    }

    private fun setupSearch(menu: Menu) {
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        mSearchMenuItem = menu.findItem(R.id.search)
        (mSearchMenuItem?.actionView as? SearchView)?.apply {
            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String) = false
            })
        }

        MenuItemCompat.setOnActionExpandListener(mSearchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                mIsSearchOpen = true
                media_refresh_layout.isEnabled = false
                return true
            }

            // this triggers on device rotation too, avoid doing anything
            override fun onMenuItemActionCollapse(item: MenuItem?) = false
        })
    }

    private fun getMediaAdapter() = media_grid.adapter as? MediaAdapter

    private fun setupAdapter() {
        val currAdapter = media_grid.adapter
        if (currAdapter == null) {
//            initZoomListener()
            val fastscroller = if (config.scrollHorizontally) media_horizontal_fastscroller else media_vertical_fastscroller
            MediaAdapter(this, mMedia as ArrayList<ThumbnailItem>, this, mIsGetImageIntent || mIsGetVideoIntent || mIsGetAnyIntent,
                    mAllowPickingMultiple, mPath, media_grid, fastscroller) {
                if (it is Medium && !isFinishing) {
                    itemClicked(it.path)
                }
            }.apply {
                setupZoomListener(mZoomListener)
                isBackgroundAdapter = true
                media_grid.adapter = this
            }
            setupLayoutManager()
        } else {
            (currAdapter as MediaAdapter).updateMedia(mMedia)
        }

        measureRecyclerViewContent(mMedia)
        setupScrollDirection()
    }

    private fun setupScrollDirection() {
        val viewType = VIEW_TYPE_LIST //config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
        val allowHorizontalScroll = false //config.scrollHorizontally && viewType == VIEW_TYPE_GRID
        media_vertical_fastscroller.isHorizontal = false
        media_vertical_fastscroller.beVisible()
        media_horizontal_fastscroller.isHorizontal = true
        media_horizontal_fastscroller.beGone()
        media_vertical_fastscroller.allowBubbleDisplay = false //config.showInfoBubble
    }

    private fun checkLastMediaChanged() {
        if (isDestroyed) {
            return
        }

        mLastMediaHandler.removeCallbacksAndMessages(null)
        mLastMediaHandler.postDelayed({
            ensureBackgroundThread {
                val mediaId = getLatestMediaId()
                val mediaDateId = getLatestMediaByDateId()
                if (mLatestMediaId != mediaId || mLatestMediaDateId != mediaDateId) {
                    mLatestMediaId = mediaId
                    mLatestMediaDateId = mediaDateId
                    runOnUiThread {
                        getMedia()
                    }
                } else {
                    checkLastMediaChanged()
                }
            }
        }, LAST_MEDIA_CHECK_PERIOD)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, false, true, mPath) {
            mLoadedInitialPhotos = false
            media_grid.adapter = null
            getMedia()
        }
    }

    private fun showFilterMediaDialog() {
        FilterMediaDialog(this) {
            mLoadedInitialPhotos = false
            media_refresh_layout.isRefreshing = true
            media_grid.adapter = null
            getMedia()
        }
    }

    private fun emptyRecycleBin() {
        showRecycleBinEmptyingDialog {
            emptyTheRecycleBin {
                finish()
            }
        }
    }

    private fun emptyAndDisableRecycleBin() {
        showRecycleBinEmptyingDialog {
            emptyAndDisableTheRecycleBin {
                finish()
            }
        }
    }

    private fun toggleFilenameVisibility() {
        config.displayFileNames = !config.displayFileNames
        getMediaAdapter()?.updateDisplayFilenames(config.displayFileNames)
    }

    private fun switchToFolderView() {
        config.showAll = false
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this, false, mPath) {
            invalidateOptionsMenu()
            setupLayoutManager()
            media_grid.adapter = null
            setupAdapter()
        }
    }

    private fun showGroupByDialog() {
        ChangeGroupingDialog(this, mPath) {
            mLoadedInitialPhotos = false
            media_grid.adapter = null
            getMedia()
        }
    }

    private fun getMedia() {
        if (mIsGettingMedia) {
            return
        }

        mIsGettingMedia = true
        if (mBackgroundType == DOWNLOAD_CACHED || mBackgroundType == DOWNLOAD_CACHED) {
            gotMedia(ArrayList<ThumbnailItem>(ServerDao.downloading.toList()), true)
        }else {
            gotMedia(ArrayList<ThumbnailItem>(ServerDao.uploading.toList()), true)
        }
        mLoadedInitialPhotos = true
      }

    private fun tryToggleTemporarilyShowHidden() {
        if (config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            handleHiddenFolderPasswordProtection {
                toggleTemporarilyShowHidden(true)
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        mLoadedInitialPhotos = false
        config.temporarilyShowHidden = show
        getMedia()
        invalidateOptionsMenu()
    }

    private fun setupLayoutManager() {
        setupListLayoutManager()
    }

    private fun measureRecyclerViewContent(media: ArrayList<ThumbnailItem>) {
        media_grid.onGlobalLayout {
            if (config.scrollHorizontally) {
                calculateContentWidth(media)
            } else {
                calculateContentHeight(media)
            }
        }
    }

    private fun calculateContentWidth(media: ArrayList<ThumbnailItem>) {
        val layoutManager = media_grid.layoutManager as MyGridLayoutManager
        val thumbnailWidth = layoutManager.getChildAt(0)?.width ?: 0
        val fullWidth = ((media.size - 1) / layoutManager.spanCount + 1) * thumbnailWidth
        media_horizontal_fastscroller.setContentWidth(fullWidth)
        media_horizontal_fastscroller.setScrollToX(media_grid.computeHorizontalScrollOffset())
    }

    private fun calculateContentHeight(media: ArrayList<ThumbnailItem>) {
        val layoutManager = media_grid.layoutManager as MyGridLayoutManager
        val pathToCheck = if (mPath.isEmpty()) SHOW_ALL else mPath
        val hasSections = config.getFolderGrouping(pathToCheck) and GROUP_BY_NONE == 0 && !config.scrollHorizontally
        val sectionTitleHeight = if (hasSections) layoutManager.getChildAt(0)?.height ?: 0 else 0
        val thumbnailHeight = if (hasSections) layoutManager.getChildAt(1)?.height ?: 0 else layoutManager.getChildAt(0)?.height ?: 0

        var fullHeight = 0
        var curSectionItems = 0
        media.forEach {
            if (it is ThumbnailSection) {
                fullHeight += sectionTitleHeight
                if (curSectionItems != 0) {
                    val rows = ((curSectionItems - 1) / layoutManager.spanCount + 1)
                    fullHeight += rows * thumbnailHeight
                }
                curSectionItems = 0
            } else {
                curSectionItems++
            }
        }

        fullHeight += ((curSectionItems - 1) / layoutManager.spanCount + 1) * thumbnailHeight
        media_vertical_fastscroller.setContentHeight(fullHeight)
        media_vertical_fastscroller.setScrollToY(media_grid.computeVerticalScrollOffset())
    }


    private fun setupListLayoutManager() {
        val layoutManager = media_grid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        layoutManager.orientation = RecyclerView.VERTICAL
        media_refresh_layout.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mZoomListener = null
    }

    private fun increaseColumnCount() {
        config.mediaColumnCnt = ++(media_grid.layoutManager as MyGridLayoutManager).spanCount
        columnCountChanged()
    }

    private fun reduceColumnCount() {
        config.mediaColumnCnt = --(media_grid.layoutManager as MyGridLayoutManager).spanCount
        columnCountChanged()
    }

    private fun columnCountChanged() {
        invalidateOptionsMenu()
        media_grid.adapter?.notifyDataSetChanged()
        measureRecyclerViewContent(mMedia)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQUEST_EDIT_IMAGE) {
            if (resultCode == Activity.RESULT_OK && resultData != null) {
                mMedia.clear()
                refreshItems()
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private fun itemClicked(path: String) {
        if (mIsGetImageIntent || mIsGetVideoIntent || mIsGetAnyIntent) {
            Intent().apply {
                data = Uri.parse(path)
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        } else {
            val isVideo = path.isVideoFast()
            if (isVideo) {
                val extras = HashMap<String, Boolean>()
                extras[SHOW_FAVORITES] = mPath == FAVORITES
                openPath(path, false, extras)
            } else {
                Intent(this, ViewPagerActivity::class.java).apply {
                    putExtra(PATH, path)
                    putExtra(SHOW_ALL, mShowAll)
                    putExtra(SHOW_FAVORITES, mPath == FAVORITES)
                    putExtra(SHOW_RECYCLE_BIN, mPath == RECYCLE_BIN)
                    startActivity(this)
                }
            }
        }
    }

    private fun gotMedia(media: ArrayList<ThumbnailItem>, isFromCache: Boolean) {
        mIsGettingMedia = false
        checkLastMediaChanged()
        mMedia = media

        if (mMedia.isEmpty()){
            finish()
        }

        runOnUiThread {
            media_refresh_layout.isRefreshing = false

            val viewType = VIEW_TYPE_LIST//config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
            val allowHorizontalScroll = false//config.scrollHorizontally && viewType == VIEW_TYPE_GRID
            media_vertical_fastscroller.beVisibleIf(media_grid.isVisible() && !allowHorizontalScroll)
            media_horizontal_fastscroller.beVisibleIf(media_grid.isVisible() && allowHorizontalScroll)
            setupAdapter()
        }

        mLatestMediaId = getLatestMediaId()
        mLatestMediaDateId = getLatestMediaByDateId()
        if (!isFromCache) {
            val mediaToInsert = (mMedia).filter { it is Medium && it.deletedTS == 0L }.map { it as Medium }
            try {
                mMediumDao.insertAll(mediaToInsert)
            } catch (e: Exception) {
            }
        }
    }

    override fun tryDeleteFiles(fileDirItems: ArrayList<FileDirItem>) {
    }

    override fun refreshItems() {
        getMedia()
    }

    override fun selectedPaths(paths: ArrayList<String>) {
        Intent().apply {
            putExtra(PICKED_PATHS, paths)
            setResult(Activity.RESULT_OK, this)
        }
        finish()
    }


}
