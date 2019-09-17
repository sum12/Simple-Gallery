package com.simplemobiletools.gallery.pro.interfaces

import androidx.room.Dao
import com.simplemobiletools.gallery.pro.models.Medium
import okhttp3.*

import retrofit2.Retrofit
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import java.io.File
import okhttp3.MultipartBody
import okhttp3.RequestBody
import com.google.gson.annotations.SerializedName
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.gallery.pro.extensions.config
import kotlinx.coroutines.*
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.util.*


class BaseResponse {

    @SerializedName("msg")
    var msg: String? = null

    @SerializedName("status")
    var status: Int = 0
}


class ServerDao(val activtiy: BaseSimpleActivity) {

    companion object{
        val uploading : Queue<Medium> = ArrayDeque<Medium>()
        val downloading : Queue<Medium> = ArrayDeque<Medium>()
        lateinit var photfloat: Service

        private fun getPhotoFloat(activtiy: BaseSimpleActivity) : Service {
            if (! ::photfloat.isInitialized) {
                val client = OkHttpClient.Builder().build()
                photfloat = Retrofit.Builder().baseUrl(activtiy.config.serverUrl).addConverterFactory(GsonConverterFactory.create()).client(client).build().create(Service::class.java)
            }
            return photfloat
        }
    }

    private fun prepareUpload(media: Medium, album_path: String) : Pair<MultipartBody.Part, RequestBody> {
        val file = File(media.path)
        val fileReqBody = RequestBody.create(MediaType.parse("image/*"), file)
        val pic = MultipartBody.Part.createFormData("pic", file.name, fileReqBody)
        val albumpath = RequestBody.create(MediaType.parse("text/plain"), album_path)
        return Pair(pic, albumpath)
    }


    private fun filter_and_queue (from :Queue<Medium>, to : Queue<Medium>) = from.filter { photo -> !to.map { p:Medium -> p.path }.contains( photo.path) }.map { photo -> to.add(photo) }

    public fun queue_upload(q: Queue<Medium>) =  filter_and_queue(q, uploading)
    public fun queue_download(q: Queue<Medium>) =  filter_and_queue(q, downloading)



    suspend fun upload(){
        CoroutineScope(Dispatchers.IO).launch {
            for (i in uploading) {
//                if (i == null) return@launch
                val pair = prepareUpload(i, i.parentPath.substringAfterLast('/'))
                val response = getPhotoFloat(activtiy).upload(pair.first, pair.second)
                withContext(Dispatchers.Main) {
                    if (response.status >= 200) {
                        uploading.remove(i)
                    } else {
                        activtiy.toast(i.name + " Failed")
                    }
                }
            }
        }
    }
}


interface Service {
    @Multipart
    @POST("upload")
    suspend fun upload(@Part pic: MultipartBody.Part, @Part("album_path") album_path: RequestBody): BaseResponse

    @GET("cache/")
    suspend fun download(@Part pic: MultipartBody.Part, @Part("album_path") album_path: RequestBody): BaseResponse

    @POST("scan")
    suspend fun scan(): BaseResponse

}