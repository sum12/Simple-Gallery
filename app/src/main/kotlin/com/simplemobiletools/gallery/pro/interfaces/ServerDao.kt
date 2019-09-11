package com.simplemobiletools.gallery.pro.interfaces

import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory


class BaseResponse {

    @SerializedName("msg")
    var msg: String? = null

    @SerializedName("status")
    var status: Int = 0
}


class ServerDao(var serverrul : String){
    private val photfloat : Service by lazy {
        val client = OkHttpClient.Builder().build()
        Retrofit.Builder().baseUrl(serverrul).addConverterFactory(GsonConverterFactory.create()).client(client).build().create(Service::class.java)
    }

    fun upload(media: Medium){
        val file = File(media.path)
        val fileReqBody = RequestBody.create(MediaType.parse("image/*"), file)
        val pic = MultipartBody.Part.createFormData("pic", file.name, fileReqBody)
        val album_path = RequestBody.create(MediaType.parse("text/plain"), media.parentPath)

        class BaseResponseCallback : Callback<BaseResponse> {
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

        }

        this.photfloat.upload(pic, album_path).enqueue(BaseResponseCallback())
    }
}


interface Service {
    @Multipart
    @POST("/upload")
    fun upload(@Part pic: MultipartBody.Part, @Part("album_path") album_path: RequestBody): Call<BaseResponse>
}   