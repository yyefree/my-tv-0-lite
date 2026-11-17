package com.lizongying.mytv0.data

import com.google.gson.annotations.SerializedName

// 显式标注 JSON 字段名：release 构建开了 R8，getter 会被重命名成 a/b/c/d，
// 只靠 -keep <fields> 在部分情况下仍拿不到值，加注解后 gson 不再依赖反射字段名。
data class ReleaseResponse(
    @SerializedName("version_code") val version_code: Int?,
    @SerializedName("version_name") val version_name: String?,
    @SerializedName("apk_name") val apk_name: String?,
    @SerializedName("apk_url") val apk_url: String?,
    // 本次版本对应的 git commit/tag。jsDelivr 对 @main 有 12 小时缓存，
    // 用具体 ref 下载才能保证拿到的是这一版，而不是半年前的缓存。
    @SerializedName("ref") val ref: String? = null,
    // GitHub Releases 里 APK 的 asset id。走 api.github.com 下载用它，
    // 这条通道没有 CDN 整树缓存的问题（实测整包 3.69MB / 3.57s）。
    @SerializedName("asset_id") val asset_id: Long? = null,
)
