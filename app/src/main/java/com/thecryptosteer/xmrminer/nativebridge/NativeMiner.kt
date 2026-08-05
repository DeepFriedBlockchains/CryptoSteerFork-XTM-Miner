package com.thecryptosteer.xmrminer.nativebridge

import com.thecryptosteer.xmrminer.service.NativeCallbacks

object NativeMiner {
    init { System.loadLibrary("cryptosteer_miner") }

    external fun create(callbacks: NativeCallbacks): Long
    external fun start(handle: Long, configJson: String): Boolean
    external fun pause(handle: Long): Boolean
    external fun resume(handle: Long): Boolean
    external fun updateDonationBasisPoints(handle: Long, basisPoints: Int): Boolean
    external fun stop(handle: Long)
    external fun destroy(handle: Long)
    external fun diagnostics(handle: Long): String
    external fun benchmark(configJson: String): String
    external fun benchmarkProgress(): String
    external fun cancelBenchmark()
    external fun autoTune(configJson: String): String
    external fun cancelAutoTune()
    external fun runSelfTests(): String
    external fun nativeVersion(): String
}
