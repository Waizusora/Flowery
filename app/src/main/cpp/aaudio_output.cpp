#include <aaudio/AAudio.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
namespace {
struct OutputState {
 std::vector<float> samples;
 std::atomic<int64_t> position{0};
 std::atomic<float> volume{1.0f};
 std::atomic<bool> active{false};
 int32_t channels=2;
};
std::mutex g_mutex;
AAudioStream* g_stream=nullptr;
std::unique_ptr<OutputState> g_output;
aaudio_data_callback_result_t dataCallback(AAudioStream*,void* userData,void* audioData,int32_t frames) {
 auto* s=static_cast<OutputState*>(userData); auto* out=static_cast<float*>(audioData);
 const int64_t total=static_cast<int64_t>(s->samples.size());
 const int64_t requested=static_cast<int64_t>(frames)*s->channels;
 const int64_t start=s->position.fetch_add(requested);
 const int64_t count=std::max<int64_t>(0,std::min<int64_t>(requested,total-start));
 const float gain=s->volume.load();
 for(int64_t i=0;i<count;++i) out[i]=std::clamp(s->samples[static_cast<size_t>(start+i)]*gain,-1.0f,1.0f);
 std::fill(out+count,out+requested,0.0f);
 if(count<requested){s->active.store(false);return AAUDIO_CALLBACK_RESULT_STOP;}
 return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
void stopLocked(){
 if(g_stream){AAudioStream_requestStop(g_stream);AAudioStream_close(g_stream);g_stream=nullptr;}
 g_output.reset();
}
aaudio_result_t openStream(OutputState* state,int32_t rate,int32_t channels,aaudio_sharing_mode_t sharing){
 AAudioStreamBuilder* b=nullptr; auto r=AAudio_createStreamBuilder(&b); if(r!=AAUDIO_OK)return r;
 AAudioStreamBuilder_setDirection(b,AAUDIO_DIRECTION_OUTPUT);
 AAudioStreamBuilder_setFormat(b,AAUDIO_FORMAT_PCM_FLOAT);
 AAudioStreamBuilder_setSampleRate(b,rate);
 AAudioStreamBuilder_setChannelCount(b,channels);
 AAudioStreamBuilder_setSharingMode(b,sharing);
 AAudioStreamBuilder_setPerformanceMode(b,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
 AAudioStreamBuilder_setUsage(b,AAUDIO_USAGE_MEDIA);
 AAudioStreamBuilder_setContentType(b,AAUDIO_CONTENT_TYPE_MUSIC);
 AAudioStreamBuilder_setDataCallback(b,dataCallback,state);
 r=AAudioStreamBuilder_openStream(b,&g_stream); AAudioStreamBuilder_delete(b);
 if(r!=AAUDIO_OK)return r;
 if(AAudioStream_getFormat(g_stream)!=AAUDIO_FORMAT_PCM_FLOAT ||
    (rate > 0 && AAudioStream_getSampleRate(g_stream)!=rate) || AAudioStream_getChannelCount(g_stream)!=channels){
  AAudioStream_close(g_stream);g_stream=nullptr;return AAUDIO_ERROR_INVALID_FORMAT;
 }
 return AAUDIO_OK;
}
}
extern "C" JNIEXPORT jint JNICALL Java_com_waizusora_flowery_NativeAudio_nativeStartOutput(JNIEnv* e,jobject,jfloatArray input,jint rate,jint channels,jfloat volume){
 if(rate<=0||(channels!=1&&channels!=2))return AAUDIO_ERROR_INVALID_FORMAT;
 jsize n=e->GetArrayLength(input);if(n<=0||n%channels!=0)return AAUDIO_ERROR_INVALID_FORMAT;
 auto state=std::make_unique<OutputState>();state->channels=channels;state->samples.resize(static_cast<size_t>(n));
 e->GetFloatArrayRegion(input,0,n,state->samples.data());if(e->ExceptionCheck())return AAUDIO_ERROR_INTERNAL;
 state->volume.store(std::clamp(volume,0.0f,1.0f));
 std::lock_guard<std::mutex> lock(g_mutex);stopLocked();g_output=std::move(state);
 auto r=openStream(g_output.get(),rate,channels,AAUDIO_SHARING_MODE_EXCLUSIVE);
 if(r!=AAUDIO_OK)r=openStream(g_output.get(),rate,channels,AAUDIO_SHARING_MODE_SHARED);
 if(r!=AAUDIO_OK){stopLocked();return r;}
 g_output->active.store(true);r=AAudioStream_requestStart(g_stream);
 if(r!=AAUDIO_OK){g_output->active.store(false);stopLocked();return r;}return 0;
}
extern "C" JNIEXPORT void JNICALL Java_com_waizusora_flowery_NativeAudio_nativeSetOutputVolume(JNIEnv*,jobject,jfloat v){
 std::lock_guard<std::mutex> lock(g_mutex);if(g_output)g_output->volume.store(std::clamp(v,0.0f,1.0f));
}
extern "C" JNIEXPORT jint JNICALL Java_com_waizusora_flowery_NativeAudio_nativePauseOutput(JNIEnv*,jobject){
 std::lock_guard<std::mutex> lock(g_mutex);return g_stream?AAudioStream_requestPause(g_stream):AAUDIO_ERROR_INVALID_STATE;
}
extern "C" JNIEXPORT jint JNICALL Java_com_waizusora_flowery_NativeAudio_nativeResumeOutput(JNIEnv*,jobject){
 std::lock_guard<std::mutex> lock(g_mutex);return g_stream?AAudioStream_requestStart(g_stream):AAUDIO_ERROR_INVALID_STATE;
}
extern "C" JNIEXPORT void JNICALL Java_com_waizusora_flowery_NativeAudio_nativeStopOutput(JNIEnv*,jobject){
 std::lock_guard<std::mutex> lock(g_mutex);if(g_output)g_output->active.store(false);stopLocked();
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_waizusora_flowery_NativeAudio_nativeIsOutputActive(JNIEnv*,jobject){
 std::lock_guard<std::mutex> lock(g_mutex);return g_output&&g_output->active.load()?JNI_TRUE:JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL Java_com_waizusora_flowery_NativeAudio_nativeSelectOutputSampleRate(JNIEnv*,jobject,jint requestedRate,jint channels){
 if(requestedRate<=0||(channels!=1&&channels!=2))return AAUDIO_ERROR_INVALID_FORMAT;
 std::lock_guard<std::mutex> lock(g_mutex);
 stopLocked();
 OutputState probe;
 probe.channels=channels;
 auto tryRate=[&](int32_t rate)->int32_t{
  auto r=openStream(&probe,rate,channels,AAUDIO_SHARING_MODE_EXCLUSIVE);
  if(r!=AAUDIO_OK)r=openStream(&probe,rate,channels,AAUDIO_SHARING_MODE_SHARED);
  if(r!=AAUDIO_OK)return 0;
  const int32_t actual=AAudioStream_getSampleRate(g_stream);
  AAudioStream_close(g_stream);g_stream=nullptr;
  return actual;
 };
 int32_t selected=tryRate(requestedRate);
 if(selected==0)selected=tryRate(0);
 return selected>0?selected:AAUDIO_ERROR_UNAVAILABLE;
}
