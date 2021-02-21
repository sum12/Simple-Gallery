package com.simplemobiletools.gallery.pro.interfaces

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.annotations.SerializedName
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.rescanPaths
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.gallery.pro.extensions.config
import com.simplemobiletools.gallery.pro.extensions.fixDateTaken
import com.simplemobiletools.gallery.pro.helpers.TYPE_IMAGES
import com.simplemobiletools.gallery.pro.models.Medium
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


class CacheResponse {
    @SerializedName("date")
    private var date : String? = null

    @SerializedName("dateTimeOriginal")
    var dateTimeOriginal : String? = null

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

data class MediumLike (
        val path: String
){
    val name : String
        get() {
            return path.substringAfterLast("/")
        }
    val parentPath : String
        get() {
            return path.substringBeforeLast("/")
        }
    companion object {
        fun from(photo:Medium) : MediumLike {
            return MediumLike(path = photo.path)
        }
        fun from(album_path: String, cacheResponse: CacheResponse): MediumLike {
            return MediumLike(path = album_path + "/" + cacheResponse.name)
        }
    }
}


class ServerDao(val activity: BaseSimpleActivity) {


    init {
        if (activity.config.serverUrl == "") {
            null
        } else {
            builder(activity.config.serverUrl)
        }
    }

    companion object {
        val logger = Logger.getLogger(ServerDao::class.java.name)
        var taskrunning = false
        val uploading = ConcurrentLinkedQueue<Medium>()
        val downloading = ConcurrentLinkedQueue<Medium>()
        val downloadingCached = ConcurrentLinkedQueue<Medium>()
        val downloadingMissing = ConcurrentLinkedQueue<MediumLike>()
        val working = ConcurrentLinkedQueue<Medium>()
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
        private fun filter_and_queue_media_like (from: ArrayList<MediumLike>, to: Queue<MediumLike>) = from.filter { photo -> !to.map { p -> p.path }.contains( photo.path) }.map { photo -> to.add(photo) }

//    fun queue_upload(q: Queue<Medium>)   {
//        uploading.addAll(q.toList())
//        uploading.forEach {
//            Logger.getLogger(ServerDao::class.java.name).info(it.name)
//        }
//    }

        fun queue_missing(q: ArrayList<MediumLike>) =  filter_and_queue_media_like(q,downloadingMissing)
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
            val pair = prepareDownload(img.name, img.parentPath.substringAfterLast("/"),false)
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


        suspend fun findMissing(media: ArrayList<Medium>, album_full_path: String, block: (ArrayList<MediumLike>) -> Unit) {

            val album_path = album_full_path.substringAfterLast("/")
            if (isAlbumCached(album_path)) {
                val cached_album = getAlbum(album_path).await()
                val cached_photo_names = cached_album.photos
                val on_phone = media.map { it.name }
                val missing = cached_album.photos.filterNot { it.name in on_phone }.map { MediumLike.from(album_full_path, it) }
                logger.info("sumit " + missing.map { it.name })

                block(missing as ArrayList<MediumLike>)
            }
        }


        private fun prepareUpload(media: Medium, album_path: String) : Pair<MultipartBody.Part, RequestBody> {
            val file = File(media.path)
            val fileReqBody = RequestBody.create(MediaType.parse("image/*"), file)
            val pic = MultipartBody.Part.createFormData("pic", file.name, fileReqBody)
            val albumpath = RequestBody.create(MediaType.parse("text/plain"), album_path)
            return Pair(pic, albumpath)
        }

        private fun prepareDownload(name: String, album_path: String, cached: Boolean) : Pair<String, String> {
            if (cached)
                return Pair(clean(album_path),clean(name+"_1024norot.jpg"))
            else{
                var media_name = name
                if (media_name.contains('.') ) {
                    val split = media_name.split('.')
                    media_name = split[0] +  "."+ split[1].toLowerCase()
                }
                return Pair(album_path,media_name)
            }
        }
    }

    suspend fun upload(){
        if (taskrunning){
            activity.toast("Task already running")
            return
        }
        val inflight = Channel<Medium>(4)
        val client = getPhotoFloat()
        val touchedAlbums = ArrayList<String>()
        CoroutineScope(Dispatchers.IO).launch {
            for (i in uploading.iterator()) {
                if (!inflight.isClosedForSend)
                {inflight.send(i)}
            }
            (!inflight.isClosedForSend) && inflight.close()
        }
        CoroutineScope(Dispatchers.IO).launch {
            taskrunning = true
            working.addAll(uploading)
            for (img in inflight){
//                val img = uploading[i]
                val pair = prepareUpload(img, img.parentPath.substringAfterLast('/'))
                lateinit var response: retrofit2.Response<BaseResponse>;
                try {
                    response = client.upload(pair.first, pair.second)
                }catch (e: java.net.SocketTimeoutException){
                    Logger.getLogger(ServerDao::class.java.name).info("could not upload "+ e.message)
                    Logger.getLogger(ServerDao::class.java.name).info(e.toString())
                    inflight.cancel(CancellationException("Failed to send: "+ e.message))
                    break
                }
                 touchedAlbums.add(img.parentPath.substringAfterLast('/'))
                if (response.code() >= 200) {
                    activity.toast(img.name + " - Done ")
                    uploading.remove(img)
                } else {
                    activity.toast(img.name + " Failed ("+response.code()+"):" + response.errorBody())
                }
            }
            getPhotoFloat().scan()
            touchedAlbums.forEach {
                albumjobs.remove(it)
            }
            taskrunning = false
        }
    }

    suspend fun download(cached: Boolean = false, missing:Boolean = false, goodownload: (InputStream, String) -> Unit){
        if (taskrunning){
            activity.toast("Task already running")
            return
        }
        val inflight = Channel<Any>(4)
        val client = getPhotoFloat()
        val touchedAlbums = ArrayList<String>()
        val currq: Queue<*>
        if (cached and  missing) {
            currq = downloadingMissing
        } else if (cached) {
            currq = downloadingCached
        } else {
            currq = downloading
        }
        CoroutineScope(Dispatchers.IO).launch {
            for (i in currq.iterator()) {
                if (!inflight.isClosedForSend) {
                    inflight.send(i)
                }
            }
            (!inflight.isClosedForSend) && inflight.close()
        }
        CoroutineScope(Dispatchers.IO).launch{
            taskrunning = true
            lateinit var name: String
            lateinit var path:String
            lateinit var parentPath:String
            for (img in inflight){
                when (img) {
                    is MediumLike -> {
                        name = img.name
                        path = img.path
                        parentPath = img.parentPath
                    }
                    is Medium -> {
                        name = img.name
                        path = img.path
                        parentPath = img.parentPath
                    }
                }
                val pair = prepareDownload(name, parentPath.substringAfterLast('/'), cached)
                touchedAlbums.add(parentPath.substringAfterLast('/'))
                val response: retrofit2.Response<ResponseBody>
                try {
                    if (cached) {
                        response = client.photoFromCache(pair.first, pair.second)
                    } else {
                        response = client.photoOriginal(pair.first, pair.second)
                    }
                } catch (e: java.lang.Exception) {
                    logger.info("Unalbe to Download " + e.toString())
                    continue
                }
                if (response.code() >= 200) {
                    try {
                        goodownload(response.body()!!.bytes().inputStream(), path)
                    }catch (e: Exception){
                        break
                        inflight.cancel()
                        inflight.close()
                    }
                    currq.remove(img)
//                    Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).info(pair.second + ": sumit removed from list")
//                    Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).info(downloading.size.toString() + ": sumit size of donwloading")
//                    Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).info(downloadingCached.size.toString() + ": sumit size of donwloading cached")
                } else {
                    activity.toast(name + " Failed" + response.errorBody())
                }
            }
            activity.rescanPaths(touchedAlbums) {
                activity.fixDateTaken(touchedAlbums, false)
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
