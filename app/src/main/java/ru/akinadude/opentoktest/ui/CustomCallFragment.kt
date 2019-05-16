package ru.akinadude.opentoktest.ui

import android.annotation.TargetApi
import android.content.Context
import android.os.Bundle
import android.support.v4.app.Fragment
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import com.nosorogstudio.topdoc.ui.telemed.video.CallEvents
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_AECDUMP_ENABLED
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_AUDIOCODEC
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_AUDIO_BITRATE
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_DATA_CHANNEL_ENABLED
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_DISABLE_BUILT_IN_AEC
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_DISABLE_BUILT_IN_AGC
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_DISABLE_BUILT_IN_NS
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_DISABLE_WEBRTC_AGC_AND_HPF
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_ENABLE_LEVEL_CONTROL
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_FLEXFEC_ENABLED
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_HWCODEC_ENABLED
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_ID
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_LOOPBACK
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_MAX_RETRANSMITS
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_MAX_RETRANSMITS_MS
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_NEGOTIATED
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_NOAUDIOPROCESSING_ENABLED
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_OPENSLES_ENABLED
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_ORDERED
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_PROTOCOL
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_TRACING
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_VIDEOCODEC
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_VIDEO_BITRATE
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_VIDEO_CALL
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_VIDEO_FPS
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_VIDEO_HEIGHT
import com.nosorogstudio.topdoc.ui.telemed.video.Constant.EXTRA_VIDEO_WIDTH
import com.nosorogstudio.topdoc.ui.telemed.video.ProxyRenderer
import kotlinx.android.synthetic.main.fragment_call2.*
import org.webrtc.*
import ru.akinadude.opentoktest.R
import ru.akinadude.opentoktest.video.AppRTCClient
import ru.akinadude.opentoktest.video.PeerConnectionClient
import java.util.*

class CustomCallFragment : Fragment(), CallEvents, PeerConnectionClient.PeerConnectionEvents {

    companion object {

        fun newInstance(): CustomCallFragment {
            return CustomCallFragment()
        }
    }

    private val TAG = CustomCallFragment::class.java.simpleName
    private val callEventsImplementation: CallEvents = this

    private var logToast: Toast? = null
    private var scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL
    private var videoCallEnabled = true
    private var peerConnectionClient: PeerConnectionClient? = null
    private var rootEglBase: EglBase? = null
    private val localProxyRenderer = ProxyRenderer()
    private val remoteProxyRenderer = ProxyRenderer()
    private val remoteRenderers = ArrayList<VideoRenderer.Callbacks>()
    // True if local view is in the fullscreen renderer.
    private var isSwappedFeeds: Boolean = false
    private var screencaptureEnabled = false

    private var peerConnectionParameters: PeerConnectionClient.PeerConnectionParameters? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_call2, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        remoteRenderers.add(remoteProxyRenderer)

        // Create video renderers.
        rootEglBase = EglBase.create()

        pip_video_view.init(rootEglBase?.getEglBaseContext(), null)
        pip_video_view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        fullscreen_video_view.init(rootEglBase?.getEglBaseContext(), null)
        fullscreen_video_view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        pip_video_view.setZOrderMediaOverlay(true)
        pip_video_view.setEnableHardwareScaler(true /* enabled */)
        fullscreen_video_view.setEnableHardwareScaler(true /* enabled */)
        // Start with local feed in fullscreen and swap it to the pip when the call is connected.
        setSwappedFeeds(true /* isSwappedFeeds */)

        button_call_disconnect.setOnClickListener {
            callEventsImplementation.onCallHangUp()
        }

        button_call_switch_camera.setOnClickListener {
            callEventsImplementation.onCameraSwitch()
        }

        button_call_toggle_mic.setOnClickListener {
            val enabled = callEventsImplementation.onToggleMic()
            button_call_toggle_mic.alpha = if (enabled) 1.0f else 0.3f
        }

        val args = arguments
        val loopback = args?.getBoolean(EXTRA_LOOPBACK, false) ?: true
        val tracing = args?.getBoolean(EXTRA_TRACING, false) ?: true

        var videoWidth = args?.getInt(EXTRA_VIDEO_WIDTH, 0) ?: 0
        var videoHeight = args?.getInt(EXTRA_VIDEO_HEIGHT, 0) ?: 0

        screencaptureEnabled = true
        // If capturing format is not specified for screencapture, use screen resolution.
        if (screencaptureEnabled && videoWidth == 0 && videoHeight == 0) {
            val displayMetrics = getDisplayMetrics()
            videoWidth = displayMetrics.widthPixels
            videoHeight = displayMetrics.heightPixels
        }

        var dataChannelParameters: PeerConnectionClient.DataChannelParameters? = null
        if (args != null && args.getBoolean(EXTRA_DATA_CHANNEL_ENABLED, false)) {
            dataChannelParameters = PeerConnectionClient.DataChannelParameters(args.getBoolean(EXTRA_ORDERED, true),
                    args.getInt(EXTRA_MAX_RETRANSMITS_MS, -1),
                    args.getInt(EXTRA_MAX_RETRANSMITS, -1),
                    args.getString(EXTRA_PROTOCOL),
                    args.getBoolean(EXTRA_NEGOTIATED, false),
                    args.getInt(EXTRA_ID, -1)
            )
        }

        peerConnectionParameters = PeerConnectionClient.PeerConnectionParameters(
                arguments?.getBoolean(EXTRA_VIDEO_CALL, true) ?: true,
                loopback,
                tracing,
                videoWidth,
                videoHeight,
                arguments?.getInt(EXTRA_VIDEO_FPS, 0) ?: 0,
                arguments?.getInt(EXTRA_VIDEO_BITRATE, 0) ?: 0,
                arguments?.getString(EXTRA_VIDEOCODEC),
                arguments?.getBoolean(EXTRA_HWCODEC_ENABLED, true) ?: true,
                arguments?.getBoolean(EXTRA_FLEXFEC_ENABLED, false) ?: false,
                arguments?.getInt(EXTRA_AUDIO_BITRATE, 0) ?: 0,
                arguments?.getString(EXTRA_AUDIOCODEC),
                arguments?.getBoolean(EXTRA_NOAUDIOPROCESSING_ENABLED, false) ?: false,
                arguments?.getBoolean(EXTRA_AECDUMP_ENABLED, false) ?: false,
                arguments?.getBoolean(EXTRA_OPENSLES_ENABLED, false) ?: false,
                arguments?.getBoolean(EXTRA_DISABLE_BUILT_IN_AEC, false) ?: false,
                arguments?.getBoolean(EXTRA_DISABLE_BUILT_IN_AGC, false) ?: false,
                arguments?.getBoolean(EXTRA_DISABLE_BUILT_IN_NS, false) ?: false,
                arguments?.getBoolean(EXTRA_ENABLE_LEVEL_CONTROL, false) ?: false,
                arguments?.getBoolean(EXTRA_DISABLE_WEBRTC_AGC_AND_HPF, false) ?: false,
                dataChannelParameters)

        peerConnectionClient = PeerConnectionClient.getInstance()
        if (true /*loopback*/) {
            val options = PeerConnectionFactory.Options()
            options.networkIgnoreMask = 0
            peerConnectionClient?.setPeerConnectionFactoryOptions(options)
        }
        peerConnectionClient?.createPeerConnectionFactory(
                activity?.application,
                peerConnectionParameters, //todo сформировать
                this@CustomCallFragment
        )

        onConnectToRoomLoopback()
    }

    @TargetApi(17)
    private fun getDisplayMetrics(): DisplayMetrics {
        val displayMetrics = DisplayMetrics()
        val windowManager = activity?.application?.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        return displayMetrics
    }

    private fun onConnectToRoomLoopback() {
        val signalingParameters = AppRTCClient.SignalingParameters(
                listOf(),
                true,
                "",
                "",
                "",
                null,
                listOf()
        )
        var videoCapturer: VideoCapturer? = null
        if (true/*peerConnectionParameters.videoCallEnabled*/) {
            videoCapturer = createVideoCapturer()
        }
        peerConnectionClient?.createPeerConnection(
                rootEglBase?.getEglBaseContext(),
                localProxyRenderer,
                remoteRenderers,
                videoCapturer,
                signalingParameters
        )

        if (signalingParameters.initiator) run {
            logAndToast("Creating OFFER...")
            // Create offer. Offer SDP will be sent to answering client in
            // PeerConnectionEvents.onLocalDescription event.
            peerConnectionClient?.createOffer()
        } else {
            if (signalingParameters.offerSdp != null) { //todo in original method param is used
                peerConnectionClient?.setRemoteDescription(signalingParameters.offerSdp)
                logAndToast("Creating ANSWER...")
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                peerConnectionClient?.createAnswer()
            }
            if (signalingParameters.iceCandidates != null) {
                // Add remote ICE candidates from room.
                for (iceCandidate in signalingParameters.iceCandidates) {
                    peerConnectionClient?.addRemoteIceCandidate(iceCandidate)
                }
            }
        }
    }

    private fun createVideoCapturer(): VideoCapturer? {
        Logging.d(TAG, "Creating capturer using camera2 API.")
        val videoCapturer = createCameraCapturer(Camera2Enumerator(activity))
        return videoCapturer
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.")
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.")
                val videoCapturer = enumerator.createCapturer(deviceName, null)

                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.")
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.")
                val videoCapturer = enumerator.createCapturer(deviceName, null)

                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        return null
    }

    private fun setSwappedFeeds(isSwappedFeeds: Boolean) {
        Logging.d(TAG, "setSwappedFeeds: $isSwappedFeeds")
        this.isSwappedFeeds = isSwappedFeeds
        localProxyRenderer.setTarget(if (isSwappedFeeds) fullscreen_video_view else pip_video_view)
        remoteProxyRenderer.setTarget(if (isSwappedFeeds) pip_video_view else fullscreen_video_view)
        fullscreen_video_view.setMirror(isSwappedFeeds)
        pip_video_view.setMirror(!isSwappedFeeds)
    }

    // Log |msg| and Toast about it.
    private fun logAndToast(msg: String) {
        Log.d(TAG, msg)
        if (logToast != null) {
            logToast?.cancel()
        }
        logToast = Toast.makeText(activity, msg, Toast.LENGTH_SHORT)
        logToast?.show()
    }

    //--- CallEvents interface ---
    override fun onCallHangUp() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onCameraSwitch() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onVideoScalingSwitch(scalingType: RendererCommon.ScalingType) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onCaptureFormatChange(width: Int, height: Int, framerate: Int) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onToggleMic(): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    //--- PeerConnectionClient.PeerConnectionEvents interface ---
    override fun onLocalDescription(sdp: SessionDescription?) {
        /*activity?.runOnUiThread {
            logAndToast("onLocalDescription, sending ${sdp?.type}")
        }*/
        Log.d(TAG, "onLocalDescription, sending ${sdp?.type}")

        // In loopback mode rename this offer to answer and route it back.
        val sdpAnswer = SessionDescription(
                SessionDescription.Type.fromCanonicalForm("answer"), sdp?.description)

        Log.d(TAG, "onLocalDescription, received remote ${sdp?.type}")
        peerConnectionClient?.setRemoteDescription(sdpAnswer)
    }

    override fun onIceCandidate(candidate: IceCandidate?) {
        Log.d(TAG, "onIceCandidate, ICE candidate $candidate")

        //appRtcClient.sendLocalIceCandidate(candidate);
        //|___->events.onRemoteIceCandidate(candidate)

        if (peerConnectionClient == null) {
            Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.")
            return
        }
        peerConnectionClient?.addRemoteIceCandidate(candidate)
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
        Log.d(TAG, "onIceCandidatesRemoved, ICE candidates $candidates")
    }

    override fun onIceConnected() {
        Log.d(TAG, "onIceConnected")
    }

    override fun onIceDisconnected() {
        Log.d(TAG, "onIceDisconnected")
    }

    override fun onPeerConnectionClosed() {
        Log.d(TAG, "onPeerConnectionClosed")
    }

    override fun onPeerConnectionStatsReady(reports: Array<out StatsReport>?) {
        Log.d(TAG, "onPeerConnectionStatsReady, reports $reports")
    }

    override fun onPeerConnectionError(description: String?) {
        Log.d(TAG, "onPeerConnectionError, description $description")
    }
}