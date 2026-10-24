/* This harness compiles the production lifecycle functions extracted by
 * check-wasapi-lifecycle.ps1. Every COM operation below is an in-memory fake. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>

typedef int HRESULT;
typedef int jboolean;
typedef int jint;
typedef int BOOL;
typedef unsigned int UINT32;
typedef unsigned char BYTE;
typedef long LONG;
typedef unsigned long DWORD;
typedef struct { int refs; } FakeCom;
typedef FakeCom IMMDeviceEnumerator;
typedef FakeCom IMMDevice;
typedef FakeCom IAudioClient;
typedef FakeCom IAudioCaptureClient;
typedef FakeCom IAudioEndpointVolume;
typedef FakeCom IUnknown;
typedef struct { int wFormatTag, nChannels, nSamplesPerSec, wBitsPerSample; } WAVEFORMATEX;
typedef struct { WAVEFORMATEX format; int SubFormat; } WAVEFORMATEXTENSIBLE;

#define JNI_FALSE 0
#define JNI_TRUE 1
#define TRUE 1
#define FALSE 0
#define RPC_E_CHANGED_MODE -2
#define SUCCEEDED(value) ((value) >= 0)
#define FAILED(value) ((value) < 0)
#define COINIT_MULTITHREADED 0
#define CLSCTX_ALL 0
#define WAVE_FORMAT_EXTENSIBLE 65534
#define WAVE_FORMAT_IEEE_FLOAT 3
#define AUDCLNT_SHAREMODE_SHARED 0
#define AUDCLNT_STREAMFLAGS_LOOPBACK 0
#define AE_LOG(...) ((void)0)

static int failure_stage, coinit_result, com_balance, uninitializes, mute_calls, restores;
static int g_mic_enabled, g_debug;
static char g_last_error[512];
static int original_mute, endpoint_mute, format_owned;
static DWORD thread_id = 1;
static FakeCom enumerator, device, audio_client, capture_client, endpoint_volume;
static WAVEFORMATEX format = { WAVE_FORMAT_IEEE_FLOAT, 2, 48000, 32 };
static int CLSID_MMDeviceEnumerator_local, IID_IMMDeviceEnumerator_local, IID_IAudioClient_local;
static int IID_IAudioCaptureClient_local, IID_IAudioEndpointVolume_local, KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;

static IMMDeviceEnumerator *g_enumerator;
static IMMDevice *g_device;
static IAudioClient *g_audio_client;
static IAudioCaptureClient *g_capture_client;
static IAudioEndpointVolume *g_endpoint_volume;
static WAVEFORMATEX *g_mix_format;
static int g_initialized, g_capture_com_owned, g_stopping, g_reading;
static DWORD g_capture_com_thread;
static int g_mute_render = 1, g_channels_req, g_rate_req, g_src_is_float, g_src_channels, g_src_rate, g_src_bps;
static int g_had_prev_mute_state, g_did_mute_endpoint;
static BOOL g_prev_mute_state;
static LONG g_atexit_registered, g_safety_net_ran;
static int16_t *g_pending, *g_src_accum, g_prev_sample[8];
static int g_pending_cap, g_pending_len, g_src_accum_cap, g_src_accum_len;
static double g_resample_pos;
static long long g_frames_captured, g_frames_delivered, g_frames_dropped;

static void set_error(const char *format_text, ...) {
    va_list arguments; va_start(arguments, format_text);
    vsnprintf(g_last_error, sizeof(g_last_error), format_text, arguments);
    va_end(arguments);
}
static HRESULT step(int number) { return failure_stage == number ? -1 : 0; }
static HRESULT initialize(void) {
    if (failure_stage == 1) return -1;
    if (SUCCEEDED(coinit_result)) com_balance++;
    return coinit_result;
}
static void uninitialize(void) { assert(com_balance > 0); com_balance--; uninitializes++; }
static HRESULT acquire(int number, FakeCom *object, void **out) {
    if (FAILED(step(number))) return -1;
    assert(object->refs == 0); object->refs++; *out = object; return 0;
}
static IMMDevice *pick_best_render_device(IMMDeviceEnumerator *source) {
    (void)source;
    if (FAILED(step(3))) return NULL;
    assert(device.refs == 0); device.refs++; return &device;
}
static HRESULT activate(const void *iid, void **out) {
    return iid == &IID_IAudioEndpointVolume_local ? acquire(4, &endpoint_volume, out)
        : acquire(7, &audio_client, out);
}
static HRESULT get_mute(BOOL *out) { if (FAILED(step(5))) return -1; *out = endpoint_mute; return 0; }
static HRESULT set_mute(BOOL value) {
    if (value) { if (FAILED(step(6))) return -1; mute_calls++; }
    else { if (FAILED(step(12))) return -1; restores++; }
    endpoint_mute = value; return 0;
}
static HRESULT get_format(WAVEFORMATEX **out) {
    if (FAILED(step(8))) return -1;
    assert(!format_owned); format_owned = 1; *out = &format; return 0;
}
static void release(FakeCom *object) { assert(object && object->refs == 1); object->refs--; }
static void free_format(void *pointer) { assert(pointer == &format && format_owned); format_owned = 0; }
static LONG exchange(LONG *target, LONG value) { LONG previous = *target; *target = value; return previous; }
static LONG compare_exchange(LONG *target, LONG value, LONG expected) {
    LONG previous = *target; if (previous == expected) *target = value; return previous;
}
static void atexit_safety_handler(void) { }
static int register_exit(void (*handler)(void)) { (void)handler; return 0; }
static HRESULT next_packet(UINT32 *size) {
    if (failure_stage == 13) return -1;
    *size = failure_stage == 14 ? 1 : 0; return 0;
}
static HRESULT capture_buffer(void) { assert(failure_stage == 14); return -1; }
/* The read-failure fixtures never enter sample conversion or allocation. */
static int16_t fetch_frame_ch(BYTE *data, UINT32 frame, int channel, DWORD flags) {
    (void)data; (void)frame; (void)channel; (void)flags; assert(0); return 0;
}
static int pending_reserve(int frames) { (void)frames; assert(0); return 0; }
static void pending_trim(int frames) { (void)frames; assert(0); }

#define CoInitializeEx(...) initialize()
#define CoUninitialize() uninitialize()
#define GetCurrentThreadId() thread_id
#define CoCreateInstance(a,b,c,d,out) acquire(2, &enumerator, out)
#define IMMDevice_Activate(dev,iid,context,activation,out) activate(iid, out)
#define IAudioEndpointVolume_GetMute(ep,out) get_mute(out)
#define IAudioEndpointVolume_SetMute(ep,value,context) set_mute(value)
#define IAudioClient_GetMixFormat(client,out) get_format(out)
#define IAudioClient_Initialize(...) step(9)
#define IAudioClient_GetService(client,iid,out) acquire(10, &capture_client, out)
#define IAudioClient_Start(client) step(11)
#define IAudioClient_Stop(client) ((void)0)
#define IAudioCaptureClient_GetNextPacketSize(client,size) next_packet(size)
#define IAudioCaptureClient_GetBuffer(...) capture_buffer()
#define IAudioCaptureClient_ReleaseBuffer(...) ((void)0)
#define IUnknown_Release(object) release((FakeCom *)(object))
#define CoTaskMemFree(pointer) free_format(pointer)
#define InterlockedExchange(pointer,value) exchange(pointer,value)
#define InterlockedCompareExchange(pointer,value,expected) compare_exchange(pointer,value,expected)
#define IsEqualGUID(left,right) ((left) == (right))
#define atexit(handler) register_exit(handler)
#define Sleep(ms) ((void)0)

static void wasapi_stop(void);
/* PRODUCTION_FUNCTIONS */

static void clean(void) {
    assert(!g_initialized && !g_enumerator && !g_device && !g_audio_client && !g_capture_client);
    assert(!g_mix_format && !format_owned && !g_endpoint_volume);
    assert(enumerator.refs == 0 && device.refs == 0 && audio_client.refs == 0);
    assert(capture_client.refs == 0 && endpoint_volume.refs == 0 && com_balance == 0);
    assert(endpoint_mute == original_mute);
}

int main(void) {
    for (int stage = 1; stage <= 11; stage++) {
        failure_stage = stage; coinit_result = 0; original_mute = endpoint_mute = 0;
        assert(wasapi_start(48000, 2) == JNI_FALSE);
        clean();
    }
    for (int already_muted = 0; already_muted <= 1; already_muted++) {
        for (int init_result = 0; init_result <= 1; init_result++) {
            failure_stage = 0; coinit_result = init_result;
            original_mute = endpoint_mute = already_muted;
            assert(wasapi_start(48000, 2) == JNI_TRUE);
            assert(endpoint_mute && g_initialized);
            wasapi_stop(); clean(); wasapi_stop(); clean();
        }
    }
    original_mute = endpoint_mute = 0;
    coinit_result = RPC_E_CHANGED_MODE;
    int before = uninitializes;
    assert(wasapi_start(48000, 2)); wasapi_stop(); clean();
    assert(uninitializes == before);
    coinit_result = 0;
    assert(wasapi_start(48000, 2));
    failure_stage = 12; wasapi_stop();
    assert(g_endpoint_volume && endpoint_mute && !g_initialized);
    assert(!wasapi_start(48000, 2)); /* Never overwrite an unrestored mute snapshot. */
    failure_stage = 0; wasapi_stop(); clean();
    assert(wasapi_start(48000, 2));
    before = uninitializes; thread_id = 2; wasapi_stop();
    assert(uninitializes == before && com_balance == 1);
    thread_id = 1; uninitialize(); clean();
    g_mute_render = 0;
    assert(wasapi_start(48000, 2)); assert(!endpoint_mute && !g_endpoint_volume);
    wasapi_stop(); clean();
    g_mute_render = 1;
    for (int stage = 13; stage <= 14; stage++) {
        failure_stage = 0;
        assert(wasapi_start(48000, 2));
        failure_stage = stage;
        int16_t samples[8];
        assert(wasapi_read(samples, 4) == -1 && !g_reading);
        assert(strstr(g_last_error, stage == 13 ? "GetNextPacketSize" : "GetBuffer"));
        assert(strstr(g_last_error, "0x"));
        /* Already collected PCM survives; the next empty failing read reports failure. */
        g_pending = (int16_t *)malloc(2 * sizeof(int16_t));
        assert(g_pending); g_pending_cap = g_pending_len = 1;
        g_pending[0] = 11; g_pending[1] = 22;
        assert(wasapi_read(samples, 4) == 1 && samples[0] == 11 && samples[1] == 22);
        assert(wasapi_read(samples, 4) == -1);
        failure_stage = 0;
        assert(wasapi_read(samples, 4) == 0); /* True quiet input remains non-fatal. */
        g_stopping = 1;
        assert(wasapi_read(samples, 4) == 0);
        wasapi_stop(); clean();
    }
    assert(mute_calls > 0 && restores > 0);
    puts("WASAPI: 11 start failure cleanups, mute restoration, COM affinity and fatal-read versus quiet-input paths passed");
    return 0;
}
