package com.malekplay.player.effects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
/** Counts successful custom GPU shader draws. Media3 does not expose an internal Lanczos draw counter. */
object VideoEnhancementMetrics {
 private val processedDraws=AtomicLong(0L)
 private val drawsByEffect=ConcurrentHashMap<String,AtomicLong>()
 @Volatile private var lastEffectName="none"
 @Volatile private var lastPresentationTimeUs=-1L
 @Volatile private var lastFailure:String?=null
 fun recordProcessed(effectName:String,presentationTimeUs:Long){lastEffectName=effectName;lastPresentationTimeUs=presentationTimeUs;lastFailure=null;processedDraws.incrementAndGet();val counter = drawsByEffect[effectName] ?: run {
            val candidate = AtomicLong(0L)
            drawsByEffect.putIfAbsent(effectName, candidate) ?: candidate
        }
        counter.incrementAndGet()}
 fun recordFailure(effectName:String,error:Throwable){lastEffectName=effectName;lastFailure="${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"}
 fun snapshot()=Snapshot(processedDraws.get(),drawsByEffect["spatial_denoise"]?.get()?:0L,drawsByEffect["detail_sharpen"]?.get()?:0L,lastEffectName,lastPresentationTimeUs,lastFailure)
 fun reset(){processedDraws.set(0L);drawsByEffect.clear();lastEffectName="none";lastPresentationTimeUs=-1L;lastFailure=null}
 data class Snapshot(val processedDraws:Long,val denoiseDraws:Long,val detailDraws:Long,val lastEffectName:String,val lastPresentationTimeUs:Long,val lastFailure:String?)
}
