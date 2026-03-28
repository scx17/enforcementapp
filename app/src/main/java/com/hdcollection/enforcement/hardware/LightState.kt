package com.hdcollection.enforcement.hardware

/**
 * 全局灯光状态跟踪，确保 LightPanelFragment 重新打开时能同步显示当前状态。
 */
object LightState {
    var flashOn = false
    var irOn = false
    var laserOn = false
    var strobeRedBlueOn = false
    var strobeRedOn = false
    var strobeBlueOn = false
}
