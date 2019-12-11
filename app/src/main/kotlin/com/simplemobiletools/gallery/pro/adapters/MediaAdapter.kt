package com.simplemobiletools.gallery.pro.adapters

import android.annotation.TargetApi
import android.content.Intent
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.bumptech.glide.Glide
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.adapters.MyRecyclerViewAdapter
import com.simplemobiletools.commons.dialogs.PropertiesDialog
import com.simplemobiletools.commons.dialogs.RenameDialog
import com.simplemobiletools.commons.dialogs.RenameItemDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.helpers.isNougatPlus
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.commons.views.FastScroller
import com.simplemobiletools.commons.views.MyRecyclerView
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.activities.BackgroundActivity
import com.simplemobiletools.gallery.pro.dialogs.DeleteWithRememberDialog
import com.simplemobiletools.gallery.pro.extensions.*
import com.simplemobiletools.gallery.pro.helpers.*
import com.simplemobiletools.gallery.pro.interfaces.MediaOperationsListener
import com.simplemobiletools.gallery.pro.interfaces.ServerDao
import com.simplemobiletools.gallery.pro.models.Medium
import com.simplemobiletools.gallery.pro.models.ThumbnailItem
import com.simplemobiletools.gallery.pro.models.ThumbnailSection
import kotlinx.android.synthetic.main.photo_video_item_grid.view.*
import kotlinx.android.synthetic.main.thumbnail_section.view.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.logging.Logger


class MediaAdapter(activity: BaseSimpleActivity, var media: MutableList<ThumbnailItem>, val listener: MediaOperationsListener?, val isAGetIntent: Boolean,
                   val allowMultiplePicks: Boolean, val path: String, recyclerView: MyRecyclerView, fastScroller: FastScroller? = null, itemClick: (Any) -> Unit) :
        MyRecyclerViewAdapter(activity, recyclerView, fastScroller, itemClick) {

    private val INSTANT_LOAD_DURATION = 2000L
    private val IMAGE_LOAD_DELAY = 100L
    private val ITEM_SECTION = 0
    private val ITEM_MEDIUM = 1

    private val config = activity.config
    private val viewType = config.getFolderViewType(if (config.showAll) SHOW_ALL else path)
    private val isListViewType : Boolean
            get() {
                if (isBackgroundAdapter)
                    return true
                else
                    return viewType == VIEW_TYPE_LIST
            }
    private var visibleItemPaths = ArrayList<String>()
    private var rotatedImagePaths = ArrayList<String>()
    private var loadImageInstantly = false
    private var delayHandler = Handler(Looper.getMainLooper())
    private var currentMediaHash = media.hashCode()
    private val hasOTGConnected = activity.hasOTGConnected()

    private var scrollHorizontally = config.scrollHorizontally
    private var animateGifs = config.animateGifs
    private var cropThumbnails = config.cropThumbnails
    private var displayFilenames = config.displayFileNames
    private var showFileTypes = config.showThumbnailFileTypes

    var isBackgroundAdapter = false

    private val mServerDao by lazy {
        ServerDao(activity)
    }

    init {
        setupDragListener(true)
        enableInstantLoad()
    }

    override fun getActionMenuId() = R.menu.cab_media

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutType = if (viewType == ITEM_SECTION) {
            R.layout.thumbnail_section
        } else {
            if (isListViewType) {
                R.layout.photo_video_item_list
            } else {
                R.layout.photo_video_item_grid
            }
        }
        return createViewHolder(layoutType, parent)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tmbItem = media.getOrNull(position) ?: return
        if (tmbItem is Medium) {
            visibleItemPaths.add(tmbItem.path)
        }

        val allowLongPress = (!isAGetIntent || allowMultiplePicks) && tmbItem is Medium
        holder.bindView(tmbItem, tmbItem is Medium, allowLongPress) { itemView, adapterPosition ->
            if (tmbItem is Medium) {
                setupThumbnail(itemView, tmbItem)
            } else {
                setupSection(itemView, tmbItem as ThumbnailSection)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = media.size

    override fun getItemViewType(position: Int): Int {
        val tmbItem = media[position]
        return if (tmbItem is ThumbnailSection) {
            ITEM_SECTION
        } else {
            ITEM_MEDIUM
        }
    }

    override fun prepareActionMode(menu: Menu) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return
        }

        val isOneItemSelected = isOneItemSelected()
        val selectedPaths = selectedItems.map { it.path } as ArrayList<String>
        val isInRecycleBin = selectedItems.firstOrNull()?.getIsInRecycleBin() == true
        menu.apply {
            findItem(R.id.cab_rename).isVisible = !isInRecycleBin
            findItem(R.id.cab_add_to_favorites).isVisible = !isInRecycleBin
            findItem(R.id.cab_fix_date_taken).isVisible = !isInRecycleBin
            findItem(R.id.cab_move_to).isVisible = !isInRecycleBin
            findItem(R.id.cab_open_with).isVisible = isOneItemSelected
            findItem(R.id.cab_confirm_selection).isVisible = isAGetIntent && allowMultiplePicks && selectedKeys.isNotEmpty()
            findItem(R.id.cab_restore_recycle_bin_files).isVisible = selectedPaths.all { it.startsWith(activity.recycleBinPath) }

            checkHideBtnVisibility(this, selectedItems)
            checkFavoriteBtnVisibility(this, selectedItems)
        }

        menu.apply {
            findItem(R.id.cab_rename).isVisible =  !isBackgroundAdapter
            findItem(R.id.cab_edit).isVisible =  !isBackgroundAdapter
            findItem(R.id.cab_hide).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_unhide).isVisible =  !isBackgroundAdapter
            findItem(R.id.cab_add_to_favorites).isVisible =  !isBackgroundAdapter
            findItem(R.id.cab_remove_from_favorites).isVisible =  !isBackgroundAdapter
            findItem(R.id.cab_restore_recycle_bin_files).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_share).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_rotate_right).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_rotate_left).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_rotate_one_eighty).isVisible= !isBackgroundAdapter
            findItem(R.id.cab_copy_to).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_move_to).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_select_all).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_open_with).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_fix_date_taken).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_set_as).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_delete).isVisible = !isBackgroundAdapter
            findItem(R.id.cab_upload).isVisible= !isBackgroundAdapter
            findItem(R.id.cab_download).isVisible= !isBackgroundAdapter
            findItem(R.id.cab_download_cached).isVisible= !isBackgroundAdapter

        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }
 isBackgroundAdapter

        when (id) {
            R.id.cab_confirm_selection -> confirmSelection()
            R.id.cab_properties -> showProperties()
            R.id.cab_rename -> renameFile()
            R.id.cab_edit -> editFile()
            R.id.cab_hide -> toggleFileVisibility(true)
            R.id.cab_unhide -> toggleFileVisibility(false)
            R.id.cab_add_to_favorites -> toggleFavorites(true)
            R.id.cab_remove_from_favorites -> toggleFavorites(false)
            R.id.cab_restore_recycle_bin_files -> restoreFiles()
            R.id.cab_share -> shareMedia()
            R.id.cab_rotate_right -> rotateSelection(90)
            R.id.cab_rotate_left -> rotateSelection(270)
            R.id.cab_rotate_one_eighty -> rotateSelection(180)
            R.id.cab_copy_to -> copyMoveTo(true)
            R.id.cab_move_to -> moveFilesTo()
            R.id.cab_select_all -> selectAll()
            R.id.cab_open_with -> openPath()
            R.id.cab_fix_date_taken -> fixDateTaken()
            R.id.cab_set_as -> setAs()
            R.id.cab_delete -> checkDeleteConfirmation()
            R.id.cab_upload-> upload()
            R.id.cab_download-> download()
            R.id.cab_download_cached-> download(cached = true)
        }
    }

    override fun getSelectableItemCount() = media.filter { it is Medium }.size

    override fun getIsItemSelectable(position: Int) = !isASectionTitle(position)

    override fun getItemSelectionKey(position: Int) = (media.getOrNull(position) as? Medium)?.path?.hashCode()

    override fun getItemKeyPosition(key: Int) = media.indexOfFirst { (it as? Medium)?.path?.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed) {
            val itemView = holder.itemView
            visibleItemPaths.remove(itemView.medium_name?.tag)
            val tmb = itemView.medium_thumbnail
            if (tmb != null) {
                Glide.with(activity).clear(tmb)
            }
        }
    }

    fun isASectionTitle(position: Int) = media.getOrNull(position) is ThumbnailSection

    private fun checkHideBtnVisibility(menu: Menu, selectedItems: ArrayList<Medium>) {
        val isInRecycleBin = selectedItems.firstOrNull()?.getIsInRecycleBin() == true
        menu.findItem(R.id.cab_hide).isVisible = !isInRecycleBin && selectedItems.any { !it.isHidden() }
        menu.findItem(R.id.cab_unhide).isVisible = !isInRecycleBin && selectedItems.any { it.isHidden() }
    }

    private fun checkFavoriteBtnVisibility(menu: Menu, selectedItems: ArrayList<Medium>) {
        menu.findItem(R.id.cab_add_to_favorites).isVisible = selectedItems.none { it.getIsInRecycleBin() } && selectedItems.any { !it.isFavorite }
        menu.findItem(R.id.cab_remove_from_favorites).isVisible = selectedItems.none { it.getIsInRecycleBin() } && selectedItems.any { it.isFavorite }
    }

    private fun confirmSelection() {
        listener?.selectedPaths(getSelectedPaths())
    }

    private fun showProperties() {
        if (selectedKeys.size <= 1) {
            val path = getFirstSelectedItemPath() ?: return
            PropertiesDialog(activity, path, config.shouldShowHidden)
        } else {
            val paths = getSelectedPaths()
            PropertiesDialog(activity, paths, config.shouldShowHidden)
        }
    }

    private fun renameFile() {
        if (selectedKeys.size == 1) {
            val oldPath = getFirstSelectedItemPath() ?: return
            RenameItemDialog(activity, oldPath) {
                ensureBackgroundThread {
                    activity.updateDBMediaPath(oldPath, it)

                    activity.runOnUiThread {
                        enableInstantLoad()
                        listener?.refreshItems()
                        finishActMode()
                    }
                }
            }
        } else {
            RenameDialog(activity, getSelectedPaths()) {
                enableInstantLoad()
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun editFile() {
        val path = getFirstSelectedItemPath() ?: return
        activity.openEditor(path)
    }

    private fun openPath() {
        val path = getFirstSelectedItemPath() ?: return
        activity.openPath(path, true)
    }

    private fun setAs() {
        val path = getFirstSelectedItemPath() ?: return
        activity.setAs(path)
    }

    private fun toggleFileVisibility(hide: Boolean) {
        ensureBackgroundThread {
            getSelectedItems().forEach {
                activity.toggleFileVisibility(it.path, hide)
            }
            activity.runOnUiThread {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun toggleFavorites(add: Boolean) {
        ensureBackgroundThread {
            val mediumDao = activity.galleryDB.MediumDao()
            getSelectedItems().forEach {
                it.isFavorite = add
                mediumDao.updateFavorite(it.path, add)
            }
            activity.runOnUiThread {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun restoreFiles() {
        activity.restoreRecycleBinPaths(getSelectedPaths()) {
            listener?.refreshItems()
            finishActMode()
        }
    }

    private fun shareMedia() {
        if (selectedKeys.size == 1 && selectedKeys.first() != -1) {
            activity.shareMediumPath(getSelectedItems().first().path)
        } else if (selectedKeys.size > 1) {
            activity.shareMediaPaths(getSelectedPaths())
        }
    }

    private fun rotateSelection(degrees: Int) {
        activity.toast(R.string.saving)
        ensureBackgroundThread {
            val paths = getSelectedPaths().filter { it.isImageFast() }
            var fileCnt = paths.size
            rotatedImagePaths.clear()
            paths.forEach {
                rotatedImagePaths.add(it)
                activity.saveRotatedImageToFile(it, it, degrees, true) {
                    fileCnt--
                    if (fileCnt == 0) {
                        activity.runOnUiThread {
                            listener?.refreshItems()
                            finishActMode()
                        }
                    }
                }
            }
        }
    }

    private fun moveFilesTo() {
        activity.handleDeletePasswordProtection {
            copyMoveTo(false)
        }
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val paths = getSelectedPaths()

        val recycleBinPath = activity.recycleBinPath
        val fileDirItems = paths.asSequence().filter { isCopyOperation || !it.startsWith(recycleBinPath) }.map {
            FileDirItem(it, it.getFilenameFromPath())
        }.toMutableList() as ArrayList

        if (!isCopyOperation && paths.any { it.startsWith(recycleBinPath) }) {
            activity.toast(R.string.moving_recycle_bin_items_disabled, Toast.LENGTH_LONG)
        }

        if (fileDirItems.isEmpty()) {
            return
        }

        activity.tryCopyMoveFilesTo(fileDirItems, isCopyOperation) {
            val destinationPath = it
            config.tempFolderPath = ""
            activity.applicationContext.rescanFolderMedia(destinationPath)
            activity.applicationContext.rescanFolderMedia(fileDirItems.first().getParentPath())

            val newPaths = fileDirItems.map { "$destinationPath/${it.name}" }.toMutableList() as ArrayList<String>
            activity.rescanPaths(newPaths) {
                activity.fixDateTaken(newPaths, false)
            }
            if (!isCopyOperation) {
                listener?.refreshItems()
                activity.updateFavoritePaths(fileDirItems, destinationPath)
            }
        }
    }

    private fun fixDateTaken() {
        ensureBackgroundThread {
            activity.fixDateTaken(getSelectedPaths(), true) {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun checkDeleteConfirmation() {
        if (config.isDeletePasswordProtectionOn) {
            activity.handleDeletePasswordProtection {
                deleteFiles()
            }
        } else if (config.tempSkipDeleteConfirmation || config.skipDeleteConfirmation) {
            deleteFiles()
        } else {
            askConfirmDelete()
        }
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val firstPath = getSelectedPaths().first()
        val items = if (itemsCnt == 1) {
            "\"${firstPath.getFilenameFromPath()}\""
        } else {
            resources.getQuantityString(R.plurals.delete_items, itemsCnt, itemsCnt)
        }

        val isRecycleBin = firstPath.startsWith(activity.recycleBinPath)
        val baseString = if (config.useRecycleBin && !isRecycleBin) R.string.move_to_recycle_bin_confirmation else R.string.deletion_confirmation
        val question = String.format(resources.getString(baseString), items)
        DeleteWithRememberDialog(activity, question) {
            config.tempSkipDeleteConfirmation = it
            deleteFiles()
        }
    }

    private fun deleteFiles() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val SAFPath = getSelectedPaths().firstOrNull { activity.needsStupidWritePermissions(it) } ?: getFirstSelectedItemPath() ?: return
        activity.handleSAFDialog(SAFPath) {
            val fileDirItems = ArrayList<FileDirItem>(selectedKeys.size)
            val removeMedia = ArrayList<Medium>(selectedKeys.size)
            val positions = getSelectedItemPositions()

            getSelectedItems().forEach {
                fileDirItems.add(FileDirItem(it.path, it.name))
                removeMedia.add(it)
            }

            media.removeAll(removeMedia)
            listener?.tryDeleteFiles(fileDirItems)
            removeSelectedItems(positions)
        }
    }

    private fun getSelectedItems() = selectedKeys.mapNotNull { getItemWithKey(it) } as ArrayList<Medium>

    private fun getSelectedPaths() = getSelectedItems().map { it.path } as ArrayList<String>

    private fun getFirstSelectedItemPath() = getItemWithKey(selectedKeys.first())?.path

    private fun getItemWithKey(key: Int): Medium? = media.firstOrNull { (it as? Medium)?.path?.hashCode() == key } as? Medium

    fun updateMedia(newMedia: ArrayList<ThumbnailItem>) {
        val thumbnailItems = newMedia.clone() as ArrayList<ThumbnailItem>
        if (thumbnailItems.hashCode() != currentMediaHash) {
            currentMediaHash = thumbnailItems.hashCode()
            Handler().postDelayed({
                media = thumbnailItems
                enableInstantLoad()
                notifyDataSetChanged()
                finishActMode()
            }, 100L)
        }
    }

    fun updateDisplayFilenames(displayFilenames: Boolean) {
        this.displayFilenames = displayFilenames
        enableInstantLoad()
        notifyDataSetChanged()
    }

    fun updateAnimateGifs(animateGifs: Boolean) {
        this.animateGifs = animateGifs
        notifyDataSetChanged()
    }

    fun updateCropThumbnails(cropThumbnails: Boolean) {
        this.cropThumbnails = cropThumbnails
        notifyDataSetChanged()
    }

    fun updateShowFileTypes(showFileTypes: Boolean) {
        this.showFileTypes = showFileTypes
        notifyDataSetChanged()
    }

    private fun enableInstantLoad() {
        loadImageInstantly = true
        delayHandler.postDelayed({
            loadImageInstantly = false
        }, INSTANT_LOAD_DURATION)
    }

    fun getItemBubbleText(position: Int, sorting: Int) = (media[position] as? Medium)?.getBubbleText(sorting, activity)

    private fun setupThumbnail(view: View, medium: Medium) {
        val isSelected = selectedKeys.contains(medium.path.hashCode())
        view.apply {
            play_outline.beVisibleIf(medium.isVideo() || medium.isPortrait())
            if (medium.isVideo()) {
                play_outline.setImageResource(R.drawable.img_play_outline)
                play_outline.beVisible()
            } else if (medium.isPortrait()) {
                play_outline.setImageResource(R.drawable.ic_portrait_photo_vector)
                play_outline.beVisibleIf(showFileTypes)
            }

            if (showFileTypes && (medium.isGIF() || medium.isRaw() || medium.isSVG())) {
                file_type.setText(when (medium.type) {
                    TYPE_GIFS -> R.string.gif
                    TYPE_RAWS -> R.string.raw
                    else -> R.string.svg
                })
                file_type.beVisible()
            } else {
                file_type.beGone()
            }

            medium_name.beVisibleIf(displayFilenames || isListViewType)
            medium_name.text = medium.name
            medium_name.tag = medium.path
            CoroutineScope(Dispatchers.Main).async {
                ServerDao.isPhotoCached(medium) {
                    medium_name.setTextColor(context.resources.getColor(R.color.md_red_500_dark))
                }
            }
            val showVideoDuration = medium.isVideo() && config.showThumbnailVideoDuration
            if (showVideoDuration) {
                video_duration.text = medium.videoDuration.getFormattedDuration()
            }
            video_duration.beVisibleIf(showVideoDuration)

            medium_check?.beVisibleIf(isSelected)
            if (isSelected) {
                medium_check?.background?.applyColorFilter(primaryColor)
            }

            var path = medium.path
            if (hasOTGConnected && context.isPathOnOTG(path)) {
                path = path.getOTGPublicPath(context)
            }

            if (loadImageInstantly) {
                activity.loadImage(medium.type, path, medium_thumbnail, scrollHorizontally, animateGifs, cropThumbnails, rotatedImagePaths)
            } else {
                medium_thumbnail.setImageDrawable(null)
                medium_thumbnail.isHorizontalScrolling = scrollHorizontally
                delayHandler.postDelayed({
                    val isVisible = visibleItemPaths.contains(medium.path)
                    if (isVisible) {
                        activity.loadImage(medium.type, path, medium_thumbnail, scrollHorizontally, animateGifs, cropThumbnails, rotatedImagePaths)
                    }
                }, IMAGE_LOAD_DELAY)
            }

            if (isListViewType) {
                medium_name.setTextColor(textColor)
                play_outline.applyColorFilter(textColor)
            }
        }
    }

    private fun setupSection(view: View, section: ThumbnailSection) {
        view.apply {
            thumbnail_section.text = section.title
            thumbnail_section.setTextColor(textColor)
        }
    }




    private fun download(cached : Boolean = false){
        ServerDao.queue_download(getSelectedItems(),cached)
        CoroutineScope(Dispatchers.Main).async{
            mServerDao.download(cached ,{ jpeg, p -> saveBitmapToFile(jpeg, p) } )
        }
        activity.startActivity(Intent(activity, BackgroundActivity::class.java).apply{
            putExtra(GET_BACKGROUND_INTENT,"download")
        })
    }

    private fun upload() {
        ServerDao.queue_upload(getSelectedItems())
        CoroutineScope(Dispatchers.Main).async{
            mServerDao.upload()
        }
        activity.startActivity(Intent(activity, BackgroundActivity::class.java ).apply{
            putExtra(GET_BACKGROUND_INTENT,"upload")
        })
    }


    private fun saveBitmapToFile(jpeg: InputStream , path: String) {
        try {
            ensureBackgroundThread {
                val file = File(path)
                val fileDirItem = FileDirItem(path, path.getFilenameFromPath())
                activity.getFileOutputStream(fileDirItem, true) {
                    if (it != null) {
                        saveBitmap(file, jpeg, it)
                    } else {
                        activity.toast("Save Failed - " + file.name)
                    }
                }
            }
        } catch (e: Exception) {
            activity.showErrorToast(e)
            throw (e)
        } catch (e: OutOfMemoryError) {
            activity.toast(R.string.out_of_memory_error)
        }
    }

    private fun saveBitmap(file: File, jpeg: InputStream, out: OutputStream) {
        activity.toast(R.string.saving)
        jpeg.copyTo(out)
        scanFinalPath(file.absolutePath)
        out.close()
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).info("closed")

    }

    private fun scanFinalPath(path: String) {
        val paths = arrayListOf(path)
        activity.rescanPaths(paths) {
            activity.fixDateTaken(paths, false)
        }
    }
}
