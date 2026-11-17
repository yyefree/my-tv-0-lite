package com.lizongying.mytv0.data

import java.io.Serializable

data class TV(
    var id: Int = -1,
    var name: String = "",
    var title: String = "",
    var description: String? = null,
    var logo: String = "",
    var image: String? = null,
    var uris: List<String> = emptyList(),
    var videoIndex: Int = 0,
    var headers: Map<String, String>? = null,
    var group: String = "",
    var sourceType: SourceType = SourceType.UNKNOWN,
    var number: Int = -1,
    var child: List<TV> = emptyList(),
    // 与 uris 一一对应：该地址所属运营商（MOBILE / TELECOM / UNICOM，空串=未知）。
    // 多运营商源里用 #ISP: 注释标注，App 据此优先选本网地址，避免「打开了但拉流很慢」的跨网地址。
    var uriIsp: List<String> = emptyList(),
    // 与 uris 一一对应：该地址所在省份（山东 / 浙江 / 河北…，空串=未知）。
    // 同省意味着走同一个骨干区域，延迟和丢包通常比跨省好，所以同省地址排在跨省前面。
    var uriRegion: List<String> = emptyList(),
) : Serializable {

    override fun toString(): String {
        return "TV{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", logo='" + logo + '\'' +
                ", image='" + image + '\'' +
                ", uris=" + uris +
                ", headers=" + headers +
                ", group='" + group + '\'' +
                ", sourceType='" + sourceType + '\'' +
                ", number=" + number +
                '}'
    }
}