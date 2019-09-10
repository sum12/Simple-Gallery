package com.simplemobiletools.gallery.pro.dialogs

import android.content.DialogInterface
import android.net.Uri
import android.util.Patterns
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.exoplayer2.util.UriUtil
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.beVisibleIf
import com.simplemobiletools.commons.extensions.getBasePath
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.extensions.config
import kotlinx.android.synthetic.main.dialog_exclude_folder.view.*
import kotlinx.android.synthetic.main.dialog_server_url.view.*

class ServerUrlDialog(val activity: BaseSimpleActivity, val saved_server_url: String, val callback: (new_server_url:String) -> Unit) {


    init {
        val view = activity.layoutInflater.inflate(R.layout.dialog_server_url, null).apply {
            this.old_server_url.text = saved_server_url
        }

        AlertDialog.Builder(activity)
                .setPositiveButton(R.string.ok) { dialog, which -> dialogConfirmed(dialog, view.new_server_url.text.toString()) }
                .setNegativeButton(R.string.cancel, null)
                .create().apply {
                    activity.setupDialogStuff(view, this)
                }
    }

    private fun dialogConfirmed(dialog:DialogInterface, new_server_url:String) {
        if (Patterns.WEB_URL.matcher(new_server_url).matches() ){
            dialog.dismiss()
            callback(new_server_url)
        }
        else{
            activity.toast("Invalid URL")
        }
    }
}
