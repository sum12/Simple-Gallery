package com.simplemobiletools.gallery.pro.dialogs

import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.beVisibleIf
import com.simplemobiletools.commons.extensions.getBasePath
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.extensions.config
import kotlinx.android.synthetic.main.dialog_exclude_folder.view.*
import kotlinx.android.synthetic.main.dialog_server_url.view.*

class ServerUrlDialog(val activity: BaseSimpleActivity, val old_server_url: String, val callback: () -> Unit) {
    val alternativePaths = getAlternativePathsList()
    var radioGroup: RadioGroup? = null

    init {
        val view = activity.layoutInflater.inflate(R.layout.dialog_server_url, null).apply {
            this.old_server_url.text = old_server_url
        }

        AlertDialog.Builder(activity)
                .setPositiveButton(R.string.ok) { dialog, which -> dialogConfirmed() }
                .setNegativeButton(R.string.cancel, null)
                .create().apply {
                    activity.setupDialogStuff(view, this)
                }
    }

    private fun dialogConfirmed() {
        val path = if (alternativePaths.isEmpty()) selectedPaths[0] else alternativePaths[radioGroup!!.checkedRadioButtonId]
        activity.config.addExcludedFolder(path)
        callback()
    }

    private fun getAlternativePathsList(): List<String> {
        val pathsList = ArrayList<String>()
        if (selectedPaths.size > 1)
            return pathsList

        val path = selectedPaths[0]
        var basePath = path.getBasePath(activity)
        val relativePath = path.substring(basePath.length)
        val parts = relativePath.split("/").filter(String::isNotEmpty)
        if (parts.isEmpty())
            return pathsList

        pathsList.add(basePath)
        if (basePath == "/")
            basePath = ""

        for (part in parts) {
            basePath += "/$part"
            pathsList.add(basePath)
        }

        return pathsList.reversed()
    }
}
