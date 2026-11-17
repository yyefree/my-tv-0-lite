package com.lizongying.mytv0

import android.view.KeyEvent

/**
 * 遥控器按键路由。
 *
 * 从 MainActivity.onKey 整体搬出来：这个 App 的交互几乎全靠遥控器按键，
 * 原来的 when 埋在 900 行的 Activity 里，改一个键就要在一大段 Fragment 切换、
 * 播放、EPG 逻辑中间找上下文。单独成类后，调键位不再需要碰 Activity 的其它部分。
 */
class KeyRouter(private val a: MainActivity) {

    fun route(keyCode: Int): Boolean {
        Logger.d(TAG, "keyCode $keyCode")

        // onCreate 还没执行完（或 Activity 正在重建）时收到的按键一律忽略，
        // 避免各分支访问未初始化的 viewModel / Fragment 而崩溃。
        if (!a.viewModelReady()) {
            Logger.d(TAG, "viewModel not ready, ignore key $keyCode")
            return false
        }

        // 设置面板开着时，任何一次按键都重新计时：原来只有个别点击才续期，
        // 用遥控器上下选开关时计时不续，手稍慢面板就在操作中途消失。
        if (a.settingVisible() &&
            keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE
        ) {
            a.settingActive()
        }

        when (keyCode) {
            // 0 键 = 回上一台（遥控器惯例位），不再当频道号输入用
            KeyEvent.KEYCODE_0 -> {
                a.switchToLastChannel()
                return true
            }

            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9,
                -> {
                a.showChannel(keyCode - 7)
                return true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                a.back()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                a.back()
                return true
            }

            KeyEvent.KEYCODE_BOOKMARK -> {
                a.showSetting()
                return true
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                a.showSetting()
                return true
            }

            KeyEvent.KEYCODE_HELP -> {
                a.showSetting()
                return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                a.showSetting()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                a.showSetting()
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                if (a.channelVisible()) {
                    a.channelFragment.playNow()
                    return true
                }

                a.showMenu()
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (a.channelVisible()) {
                    a.channelFragment.playNow()
                    return true
                }

                // 设置面板开着时，OK 键必须落到面板里当前有焦点的控件上。
                // 原来这里无条件弹出频道菜单，把按键从焦点按钮手里抢走：
                // 多数电视遥控器的「确定」键就是 DPAD_CENTER，结果点任何设置项都没反应
                // （只有发 ENTER(66) 才有效），用户看到的就是「点了没反应」。
                if (a.settingVisible()) {
                    val focused = a.settingFragment.view?.findFocus()
                        ?: a.window.decorView.findFocus()
                    if (focused != null && focused.isClickable && focused.isEnabled) {
                        focused.performClick()
                    }
                    return true
                }

                a.showMenu()
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                a.channelUp()
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                a.channelUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                a.channelDown()
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                a.channelDown()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                a.showProgram()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                a.showSetting()
            }
        }
        return false
    }

    companion object {
        private const val TAG = "KeyRouter"
    }
}
