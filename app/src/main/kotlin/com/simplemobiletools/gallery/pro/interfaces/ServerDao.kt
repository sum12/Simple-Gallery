package com.simplemobiletools.gallery.pro.interfaces

import com.simplemobiletools.gallery.pro.models.Medium
import okhttp3.*

import retrofit2.Retrofit
import java.io.File
import okhttp3.MultipartBody
import okhttp3.RequestBody
import com.google.gson.annotations.SerializedName
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.gallery.pro.extensions.config
import kotlinx.coroutines.*
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


class CacheResponse {
    @SerializedName("date")
    private var date : String? = null

    @SerializedName("path")
    lateinit var path : String

    @SerializedName("name")
    lateinit var name : String

    @SerializedName("photos")
    lateinit var photos : MutableList<CacheResponse>

    @SerializedName("albums")
    lateinit var albums : MutableList<CacheResponse>
}

class BaseResponse {
    @SerializedName("msg")
    var msg: String? = null
}


class ServerDao(val activtiy: BaseSimpleActivity) {


    init {
        if (activtiy.config.serverUrl == "") {
            null
        } else {
            builder(activtiy.config.serverUrl)
        }
    }

    companion object {
        var taskrunning = false
        val uploading = ConcurrentLinkedQueue<Medium>()
        val downloading = ConcurrentLinkedQueue<Medium>()
        val downloadingCached = ConcurrentLinkedQueue<Medium>()
        val albumjobs: HashMap<String, Deferred<CacheResponse>> = HashMap()

        private lateinit var _builder : Retrofit.Builder
        lateinit var photfloat: Service

        private lateinit var rootjob: Deferred<CacheResponse>

        fun builder(serverUrl:String){
            if (::_builder.isInitialized)
                return
            _builder = Retrofit.Builder().baseUrl(serverUrl)
        }
        private fun getPhotoFloat() : Service {
//            if (! ::photfloat.isInitialized) {
            val client = OkHttpClient.Builder().build()
            photfloat = _builder.addConverterFactory(GsonConverterFactory.create()).client(client).build().create(Service::class.java)
//            }
            return photfloat
        }

        suspend fun getRoot() : Deferred<CacheResponse> {
            if (::rootjob.isInitialized){
                return rootjob
            }
            rootjob = CoroutineScope(Dispatchers.IO).async {
                getPhotoFloat().root()
            }
            return rootjob
        }
        suspend fun getAlbum(path : String) :Deferred<CacheResponse> {
            val albumjb = albumjobs.get(path)
            if (albumjb == null){
                albumjobs[path] = CoroutineScope(Dispatchers.IO).async {
                    getPhotoFloat().albumJson(path)
                }
            }
            return albumjobs[path]!!
        }

        private fun filter_and_queue (from: ArrayList<Medium>, to: Queue<Medium>) = from.filter { photo -> !to.map { p -> p.path }.contains( photo.path) }.map { photo -> to.add(photo) }

//    fun queue_upload(q: Queue<Medium>)   {
//        uploading.addAll(q.toList())
//        uploading.forEach {
//            Logger.getLogger(ServerDao::class.java.name).info(it.name)
//        }
//    }

        fun queue_download(q: ArrayList<Medium>) =  filter_and_queue(q, downloading)
        fun queue_upload(q: ArrayList<Medium>) =  filter_and_queue(q, uploading)

        private fun clean(_path:String, withoutslash: Boolean = true) :String{
            var path = _path
            if (withoutslash)
                path = path.replace('/', '-')
            path = path.replace(" ", "_").replace("(","").
                    replace("&", "").
                    replace(",", "").
                    replace(")", "").
                    replace("#", "").
                    replace("[", "").
                    replace("]", "").
                    replace("\"", "").
                    replace("'", "").
                    replace("_-_", "-").toLowerCase()
            while (path.indexOf("--") != -1)
                path = path.replace("--", "-")
            while (path.indexOf("__") != -1)
                path = path.replace("__", "_")
            if (path.length == 0)
                path = "root.json"
            return path
        }

        suspend fun isAlbumCached(_subpath : String) : Boolean {
            val subpath = clean(_subpath)
            val cached = getRoot().await()
            return cached.albums.find { it.path == _subpath } != null
        }

        suspend fun isPhotoCached(path: String, block: () -> Unit ) : Boolean {
            val albumPath = path.getParentPath().substringAfterLast('/')
            val name = path.substringAfterLast('/')
            if (isAlbumCached(albumPath)) {
                val cached = getRoot().await()
                val found = clean(cached.albums.find { it.path == albumPath }?.path!!)

                cached.albums.add(getAlbum(found).await())
                if (albumjobs.get(found)!!.await().photos.find { it.name == name } != null){
                    block()
                }
            }
            return false
        }

    }

    private fun prepareUpload(media: Medium, album_path: String) : Pair<MultipartBody.Part, RequestBody> {
        val file = File(media.path)
        val fileReqBody = RequestBody.create(MediaType.parse("image/*"), file)
        val pic = MultipartBody.Part.createFormData("pic", file.name, fileReqBody)
        val albumpath = RequestBody.create(MediaType.parse("text/plain"), album_path)
        return Pair(pic, albumpath)
    }


    private fun prepareDownload(media: Medium, album_path: String, cached: Boolean) : Pair<String, String> {
        if (cached)
            return Pair(clean(album_path),clean(media.path.substringAfter('/')))
        else
            return Pair(album_path,media.path.substringAfterLast('/'))
    }

    suspend fun upload(){
        if (taskrunning){
            activtiy.toast("Task already running")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            taskrunning = true
//            for (img in uploading.toTypedArray()) {
//                if (i == null) return@launch
            val itr = uploading
            for (img in itr){
                itr.remove()
//                val img = uploading[i]
                val pair = prepareUpload(img, img.parentPath.substringAfterLast('/'))
                val response = getPhotoFloat().upload(pair.first, pair.second)
                withContext(Dispatchers.Main) {
                    if (response.code() >= 200) {
                        activtiy.toast(img.name + " - Done ")
                    } else {
                        activtiy.toast(img.name + " Failed ("+response.code()+"):" + response.errorBody())
                    }
                }
            }
            getPhotoFloat().scan()
            taskrunning = false
        }
    }

    suspend fun download(cached : Boolean = false){
        if (taskrunning){
            activtiy.toast("Task already running")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            taskrunning = true
            val currq: Queue<Medium>
            if (cached){
                currq = downloadingCached
            }else {
                currq = downloading
            }
            for (i in currq.iterator()) {
                val pair = prepareDownload(i, i.parentPath.substringAfterLast('/'), cached)
                var response: retrofit2.Response<ResponseBody>
                if (cached){
                    response = getPhotoFloat().photoFromCache(pair.first, pair.second)
                }else {
                    response = getPhotoFloat().photoOriginal(pair.first, pair.second)
                }
                if (response.code() >= 200) {
                    currq.remove(i)
                } else {
                    activtiy.toast(i.name + " Failed" + response.errorBody())
                }
            }
            taskrunning = false
        }
    }



}


interface Service {
    @Multipart
    @POST("upload")
    suspend fun upload(@Part pic: MultipartBody.Part, @Part("album_path") album_path: RequestBody): retrofit2.Response<BaseResponse>

    @GET("cache/{album_path}.json")
    suspend fun albumJson(@Path("album_path") album_path: String): CacheResponse

    @GET("cache/{album_path}/{photo_name}")
    suspend fun photoFromCache(@Path("album_path") album_path: String, @Path("photo_name") photo_name: String): retrofit2.Response<ResponseBody>

    @GET("albums/{album_path}/{photo_name}")
    suspend fun photoOriginal(@Path("album_path") album_path: String, @Path("photo_name") photo_name: String): retrofit2.Response<ResponseBody>

    @GET("cache/root.json")
    suspend fun root(): CacheResponse

    @POST("scan")
    suspend fun scan(): BaseResponse

}