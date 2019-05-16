package com.nosorogstudio.topdoc.ui.telemed.video

import org.webrtc.Logging
import org.webrtc.VideoRenderer

class ProxyRenderer : VideoRenderer.Callbacks {
    private val TAG = ProxyRenderer::class.java.simpleName
    private var target: VideoRenderer.Callbacks? = null

    @Synchronized
    override fun renderFrame(frame: VideoRenderer.I420Frame) {
        if (target == null) {
            Logging.d(TAG, "Dropping frame in proxy because target is null.")
            VideoRenderer.renderFrameDone(frame)
            return
        }

        target!!.renderFrame(frame)
    }

    @Synchronized
    fun setTarget(target: VideoRenderer.Callbacks?) {
        this.target = target
    }
}