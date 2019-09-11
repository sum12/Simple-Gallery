package com.simplemobiletools.gallery.pro.asynctasks

import android.os.AsyncTask
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.gallery.pro.interfaces.ServerDao
import com.simplemobiletools.gallery.pro.models.Medium


class BackgroudUpload(val activity: BaseSimpleActivity, val worker: ServerDao) : AsyncTask<Pair<ArrayList<Medium>, String>, String, Boolean>() {



    override fun onPreExecute() {
//        val activity = activityReference.get()
//        if (activity == null || activity.isFinishing) return
        //activity.progressBar.visibility = View.VISIBLE
    }

    override fun doInBackground(vararg params: Pair<ArrayList<Medium>, String>): Boolean? {

        val toupload = params[0].first
        for (media in toupload) {
            try {
                worker.upload(media, params[0].second)
            } catch (e: Exception){
                activity.toast(e.toString())
            }
        }
        return true
    }


    override fun onPostExecute(result: Boolean) {
//        val activity = activityReference.get()
//        if (activity == null || activity.isFinishing) return
//        activity.progressBar.visibility = View.GONE
//        activity.textView.text = result.let { it }
//        activity.myVariable = 100
    }

    override fun onProgressUpdate(vararg text: String) {
        activity.toast(text.firstOrNull().toString())
    }
}
