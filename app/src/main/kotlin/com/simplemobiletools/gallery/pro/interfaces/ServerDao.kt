package com.simplemobiletools.gallery.pro.interfaces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.InputStream
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
        lateinit var photfloat: PhotoFloatService

        private lateinit var rootjob: Deferred<CacheResponse>

        fun builder(serverUrl:String){
            if (::_builder.isInitialized)
                return
            _builder = Retrofit.Builder().baseUrl(serverUrl)
        }
        private fun getPhotoFloat() : PhotoFloatService {
//            if (! ::photfloat.isInitialized) {
            val client = OkHttpClient.Builder().build()
            photfloat = _builder.addConverterFactory(GsonConverterFactory.create()).client(client).build().create(PhotoFloatService::class.java)
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
                    getPhotoFloat().albumJson(clean(path))
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

        fun queue_download(q: ArrayList<Medium>, cached: Boolean) =  filter_and_queue(q, if (cached==false) downloading else downloadingCached)
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
//            val subpath = clean(_subpath)
            val cached = getRoot().await()
            return cached.albums.find { it.path == _subpath } != null
        }

        suspend fun isPhotoCached(img: Medium, block: () -> Unit ) : Boolean {
//            val albumPath = img.getParentPath().substringAfterLast('/')
//            val name = path.substringAfterLast('/')
            val pair = prepareDownload(img, false)
            val album_path = pair.first
            val name = pair.second
            if (isAlbumCached(album_path)) {
                val cached_album = getAlbum(album_path).await()
                if (cached_album.photos.find {
                            //Logger.getLogger(ServerDao::class.java.name).info(" " + it.name + " "+name)
                            it.name.toLowerCase() == name.toLowerCase()
                        } != null){
                    block()
                    return true
                }
                else {
                    Logger.getLogger(ServerDao::class.java.name).info("no cached !!!!!! " + name)
                }
            }
            return false
        }


        private fun prepareUpload(media: Medium, album_path: String) : Pair<MultipartBody.Part, RequestBody> {
            val file = File(media.path)
            val fileReqBody = RequestBody.create(MediaType.parse("image/*"), file)
            val pic = MultipartBody.Part.createFormData("pic", file.name, fileReqBody)
            val albumpath = RequestBody.create(MediaType.parse("text/plain"), album_path)
            return Pair(pic, albumpath)
        }

        private fun prepareDownload(media: Medium, cached: Boolean) : Pair<String, String> {
            val album_path = media.parentPath.substringAfterLast('/')
            if (cached)
                return Pair(clean(album_path),clean(media.name+"_1024.jpg"))
            else{
                var media_name = media.name;
                if (media.name.contains('.') ) {
                    val split = media.name.split('.')
                    media_name = split[0] +  "."+ split[1].toLowerCase()
                }
                return Pair(album_path,media_name)
            }
        }

    }

    suspend fun upload(){
        val touchedAlbums = ArrayList<String>()
        if (taskrunning){
            activtiy.toast("Task already running")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            taskrunning = true
            val itr = uploading.iterator()
            for (img in itr){
                itr.remove()
//                val img = uploading[i]
                val pair = prepareUpload(img, img.parentPath.substringAfterLast('/'))
                val response = getPhotoFloat().upload(pair.first, pair.second)
                touchedAlbums.add(img.parentPath.substringAfterLast('/'))
                if (response.code() >= 200) {
                    activtiy.toast(img.name + " - Done ")
                } else {
                    activtiy.toast(img.name + " Failed ("+response.code()+"):" + response.errorBody())
                    break
                }
            }
            getPhotoFloat().scan()
            touchedAlbums.forEach {
                albumjobs.remove(it)
            }
            taskrunning = false
        }
    }

    suspend fun download(cached: Boolean = false, goodownload: (InputStream, String) -> Unit){
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
                val pair = prepareDownload(i, cached)
                var response: retrofit2.Response<ResponseBody>
                if (cached){
                    response = getPhotoFloat().photoFromCache(pair.first, pair.second)
                }else {
                    response = getPhotoFloat().photoOriginal(pair.first, pair.second)
                }
                if (response.code() >= 200) {
                    try {
                        goodownload(response.body()!!.bytes().inputStream(), i.path)
                    }catch (e: Exception){
                        break
                    }
                    currq.remove(i)
                } else {
                    activtiy.toast(i.name + " Failed" + response.errorBody())
                }
            }
            taskrunning = false
        }

    }
}



interface PhotoFloatService {
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