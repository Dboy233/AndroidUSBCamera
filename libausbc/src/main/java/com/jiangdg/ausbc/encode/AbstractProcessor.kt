/*
 * Copyright 2017-2022 Jiangdg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jiangdg.ausbc.encode

import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.encode.bean.RawData
import com.jiangdg.ausbc.encode.muxer.Mp4Muxer
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.Utils
import java.lang.Exception
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean


/** Abstract processor
 *
 * @author Created by jiangdg on 2022/2/10
 */
abstract class AbstractProcessor(private val gLESRender: Boolean = false) {
    private var mEncodeThread: HandlerThread? = null
    private var mEncodeHandler: Handler? = null
    protected var mMediaCodec: MediaCodec? = null
    private var mMp4Muxer: Mp4Muxer? = null
    private var isVideo: Boolean = false
    private var mEncodeDataCb: IEncodeDataCallBack? = null
    protected val mRawDataQueue: ConcurrentLinkedQueue<RawData> = ConcurrentLinkedQueue()
    protected var mMainHandler: Handler = Handler(Looper.getMainLooper())

    protected val mEncodeState: AtomicBoolean by lazy {
        AtomicBoolean(false)
    }


    protected val mMsgState: AtomicBoolean by lazy {
        AtomicBoolean(false)
    }

    /**
     * Start encode
     *
     * [getThreadName] diff audio thread or  video thread
     */
    fun startEncode() {
        mEncodeThread = HandlerThread(this.getThreadName())
        mEncodeThread?.start()
        mEncodeHandler = Handler(mEncodeThread!!.looper) { msg ->
            when (msg.what) {
                MSG_START -> {
                    mMsgState.set(true)
                    Logger.i("Dboy","开始解码"+this)
                    handleStartEncode()
                }
                MSG_STOP -> {
                    handleStopEncode()
                    mEncodeThread?.quitSafely()
                    mEncodeThread = null
                    mEncodeHandler = null
                    mEncodeDataCb = null
                    mMsgState.set(false)
                    Logger.i("Dboy","结束解码"+this)
                }
            }
            true
        }
        mEncodeHandler?.obtainMessage(MSG_START)?.sendToTarget()
    }

    /**
     * Stop encode
     */
    fun stopEncode() {
        mEncodeState.set(false)
        mEncodeHandler?.obtainMessage(MSG_STOP)?.sendToTarget()
    }

    /**
     * Add encode data call back
     *
     * @param callBack aac or h264 data call back, see [IEncodeDataCallBack]
     */
    fun addEncodeDataCallBack(callBack: IEncodeDataCallBack?) {
        this.mEncodeDataCb = callBack
    }

    /**
     * Set mp4muxer
     *
     * @param muxer mp4 media muxer
     * @param isVideo data type, audio or video
     */
    @Synchronized
    fun setMp4Muxer(muxer: Mp4Muxer, isVideo: Boolean) {
        this.mMp4Muxer = muxer
        this.isVideo = isVideo
    }

    /**
     * Put raw data
     *
     * @param data media data, pcm or yuv
     */
    fun putRawData(data: RawData) {
        if (! mEncodeState.get()) {
            return
        }
        if (mRawDataQueue.size >= MAX_QUEUE_SIZE) {
            mRawDataQueue.poll()
        }
        mRawDataQueue.offer(data)
    }

    /**
     * Is encoding
     */
    fun isEncoding() = mEncodeState.get()

    /**
     * 获取真实状态
     */
    fun getMsgState() = mMsgState.get()

    /**
     * Get thread name
     *
     * @return Get encode thread name
     */
    protected abstract fun getThreadName(): String

    /**
     * Handle start encode
     */
    protected abstract fun handleStartEncode()

    /**
     * Handle stop encode
     */
    protected abstract fun handleStopEncode()

    /**
     * Get presentation time
     *
     * @param bufferSize buffer size
     * @return presentation time in us
     */
    protected abstract fun getPTSUs(bufferSize: Int): Long

    /**
     * Is lower lollipop
     */
    protected fun isLowerLollipop() = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP

    /**
     * Do encode data
     */
    protected fun doEncodeData() {
        while (mEncodeState.get()) {
            try {
                queueFrameIfNeed()
                val bufferInfo = MediaCodec.BufferInfo()
                var outputIndex = 0
                do {
                    mMediaCodec?.let { codec ->
                        outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMES_OUT_US)
                        when (outputIndex) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (Utils.debugCamera) {
                                    Logger.i(TAG, "addTracker is video = $isVideo")
                                }
                                mMp4Muxer?.addTracker(mMediaCodec?.outputFormat, isVideo)
                            }
                            else -> {
                                if (outputIndex < 0) {
                                    return@let
                                }
                                val outputBuffer = if (isLowerLollipop()) {
                                    codec.outputBuffers[outputIndex]
                                } else {
                                    codec.getOutputBuffer(outputIndex)
                                }
                                if (outputBuffer != null) {
                                    val encodeData = ByteArray(bufferInfo.size)
                                    outputBuffer.get(encodeData)
                                    val type = if (isVideo) {
                                        IEncodeDataCallBack.DataType.H264
                                    } else {
                                        IEncodeDataCallBack.DataType.AAC
                                    }
                                    mEncodeDataCb?.onEncodeData(encodeData, encodeData.size, type)
                                    mMp4Muxer?.pumpStream(outputBuffer, bufferInfo, isVideo)
                                    logSpecialFrame(bufferInfo, encodeData.size)
                                }
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                } while (outputIndex >= 0)
            } catch (e: Exception) {
                Logger.e(TAG, "doEncodeData failed, video = ${isVideo}， err = ${e.localizedMessage}", e)
            }
        }
    }

    private fun queueFrameIfNeed() {
        mMediaCodec?.let { codec ->
            if (mRawDataQueue.isEmpty()) {
                return@let
            }
            if (gLESRender && isVideo) {
                return@let
            }
            val rawData = mRawDataQueue.poll() ?: return@let
            val inputIndex = codec.dequeueInputBuffer(TIMES_OUT_US)
            if (inputIndex < 0) {
                return@let
            }
            val inputBuffer = if (isLowerLollipop()) {
                codec.inputBuffers[inputIndex]
            } else {
                codec.getInputBuffer(inputIndex)
            }
            inputBuffer?.clear()
            inputBuffer?.put(rawData.data)
            codec.queueInputBuffer(inputIndex, 0, rawData.data.size, getPTSUs(rawData.data.size), 0)
            if (Utils.debugCamera) {
                Logger.i(TAG, "queue mediacodec data, isVideo=$isVideo, len=${rawData.data.size}")
            }
        }
    }

    private fun logSpecialFrame(bufferInfo: MediaCodec.BufferInfo, length: Int) {
        if (length == 0 || !isVideo) {
            return
        }
        if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
            Logger.i(TAG, "isVideo = $isVideo, Key frame, len = $length")
        } else if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
            Logger.i(TAG, "isVideo = $isVideo, Pps/sps frame, len = $length")
        }
    }

    companion object {
        private const val TAG = "AbstractProcessor"
        private const val MSG_START = 1
        private const val MSG_STOP = 2
        private const val TIMES_OUT_US = 10000L

        const val MAX_QUEUE_SIZE = 10
    }

}