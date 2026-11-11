package uz.ms.videostudio

/** stable-diffusion.cpp dvigateliga ko'prik (app/src/main/cpp/sd_jni.cpp). */
object SdNative {

    interface ProgressListener {
        /** Dvigatel oqimidan chaqiriladi. */
        fun onProgress(step: Int, steps: Int, secondsPerStep: Float)
    }

    init {
        System.loadLibrary("sdjni")
    }

    external fun load(modelPath: String, threads: Int): Boolean

    external fun release()

    external fun cancel()

    external fun lastError(): String

    /** Natija: [kenglik, balandlik, ARGB piksellar...] yoki xato bo'lsa null. */
    external fun generate(
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        lcm: Boolean,
        listener: ProgressListener?,
    ): IntArray?
}
