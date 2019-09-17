package com.simplemobiletools.gallery.pro.interfaces

import com.simplemobiletools.gallery.pro.models.Medium
import okhttp3.*

import retrofit2.Call;
import retrofit2.Retrofit
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import java.io.File
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Callback
import com.google.gson.annotations.SerializedName
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.gallery.pro.extensions.config
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET



class BaseResponse {

    @SerializedName("msg")
    var msg: String? = null

    @SerializedName("status")
    var status: Int = 0
}


class ServerDao{

    companion object (activtiy: BaseSimpleActivity){
        private val photfloat : Service by lazy {
            val client = OkHttpClient.Builder().build()
            Retrofit.Builder().baseUrl(activtiy.config.serverUrl).addConverterFactory(GsonConverterFactory.create()).client(client).build().create(Service::class.java)
        }
    }

    fun upload(media: Medium, album_path: String){
        val file = File(media.path)
        val fileReqBody = RequestBody.create(MediaType.parse("image/*"), file)
        val pic = MultipartBody.Part.createFormData("pic", file.name, fileReqBody)
        val album_path = RequestBody.create(MediaType.parse("text/plain"), album_path)

        class BaseResponseCallback : Callback<BaseResponse> {
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                throw t
            }

            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
            }

        }

        photfloat.upload(pic, album_path).enqueue(BaseResponseCallback())
    }
}


interface Service {
    @Multipart
    @POST("upload")
    fun upload(@Part pic: MultipartBody.Part, @Part("album_path") album_path: RequestBody): Call<BaseResponse>

    @GET("cache/")
    fun download(@Part pic: MultipartBody.Part, @Part("album_path") album_path: RequestBody): Call<BaseResponse>

    @POST("scan")
    fun scan(): Call<BaseResponse>

}