package com.lizongying.mytv0.data

import com.google.gson.annotations.SerializedName

// 显式标注 JSON 字段名：release 构建开了 R8，getter 会被重命名成 a/b/c/d，
// 只靠 -keep <fields> 在部分情况下仍拿不到值，加注解后 gson 不再依赖反射字段名。
data class ReleaseResponse(
    @SerializedName("version_code") val version_code: Int?,
    @SerializedName("version_name") val version_name: String?,
    @SerializedName("apk_name") val apk_name: String?,
    @SerializedName("apk_url") val apk_url: String?,
)
