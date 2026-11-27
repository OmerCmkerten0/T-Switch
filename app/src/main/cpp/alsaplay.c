#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Exact 40-byte AOSP tinyalsa pcm_config layout verified from audio.primary.msm8937.so */
struct tiny_pcm_config {
    unsigned int channels;
    unsigned int rate;
    unsigned int period_size;
    unsigned int period_count;
    int format; /* 0 = PCM_FORMAT_S16_LE */
    unsigned int start_threshold;
    unsigned int stop_threshold;
    unsigned int silence_threshold;
    unsigned int silence_size;
    int avail_min;
};

struct pcm;
struct mixer;
struct mixer_ctl;

typedef struct pcm *(*pcm_open_fn)(unsigned int card, unsigned int device,
                                   unsigned int flags,
                                   const struct tiny_pcm_config *config);
typedef int (*pcm_is_ready_fn)(const struct pcm *pcm);
typedef const char *(*pcm_get_error_fn)(const struct pcm *pcm);
typedef int (*pcm_write_fn)(struct pcm *pcm, const void *data, unsigned int count);
typedef int (*pcm_prepare_fn)(struct pcm *pcm);
typedef int (*pcm_close_fn)(struct pcm *pcm);

typedef struct mixer *(*mixer_open_fn)(unsigned int card);
typedef void (*mixer_close_fn)(struct mixer *mixer);
typedef struct mixer_ctl *(*mixer_get_ctl_by_name_fn)(struct mixer *mixer, const char *name);
typedef unsigned int (*mixer_ctl_get_num_values_fn)(const struct mixer_ctl *ctl);
typedef int (*mixer_ctl_get_array_fn)(const struct mixer_ctl *ctl, void *array, size_t count);
typedef int (*mixer_ctl_set_array_fn)(struct mixer_ctl *ctl, const void *array, size_t count);
typedef int (*mixer_ctl_set_value_fn)(struct mixer_ctl *ctl, unsigned int id, int value);

static pcm_open_fn p_pcm_open = NULL;
static pcm_is_ready_fn p_pcm_is_ready = NULL;
static pcm_get_error_fn p_pcm_get_error = NULL;
static pcm_write_fn p_pcm_write = NULL;
static pcm_prepare_fn p_pcm_prepare = NULL;
static pcm_close_fn p_pcm_close = NULL;

static mixer_open_fn p_mixer_open = NULL;
static mixer_close_fn p_mixer_close = NULL;
static mixer_get_ctl_by_name_fn p_mixer_get_ctl_by_name = NULL;
static mixer_ctl_get_num_values_fn p_mixer_ctl_get_num_values = NULL;
static mixer_ctl_get_array_fn p_mixer_ctl_get_array = NULL;
static mixer_ctl_set_array_fn p_mixer_ctl_set_array = NULL;
static mixer_ctl_set_value_fn p_mixer_ctl_set_value = NULL;

static volatile sig_atomic_t g_stop = 0;

static void on_signal(int sig) {
    (void)sig;
    g_stop = 1;
}

static void *open_tinyalsa(void) {
#if defined(__LP64__)
    const char *candidates[] = {"/system/lib64/libtinyalsa.so", "libtinyalsa.so", NULL};
#else
    const char *candidates[] = {"/system/lib/libtinyalsa.so", "libtinyalsa.so", NULL};
#endif
    for (int i = 0; candidates[i] != NULL; i++) {
        void *h = dlopen(candidates[i], RTLD_NOW);
        if (h != NULL) {
            return h;
        }
    }
    return NULL;
}

static int resolve_symbols(void *handle) {
    p_pcm_open = (pcm_open_fn)dlsym(handle, "pcm_open");
    p_pcm_is_ready = (pcm_is_ready_fn)dlsym(handle, "pcm_is_ready");
    p_pcm_get_error = (pcm_get_error_fn)dlsym(handle, "pcm_get_error");
    p_pcm_write = (pcm_write_fn)dlsym(handle, "pcm_write");
    p_pcm_prepare = (pcm_prepare_fn)dlsym(handle, "pcm_prepare");
    p_pcm_close = (pcm_close_fn)dlsym(handle, "pcm_close");

    p_mixer_open = (mixer_open_fn)dlsym(handle, "mixer_open");
    p_mixer_close = (mixer_close_fn)dlsym(handle, "mixer_close");
    p_mixer_get_ctl_by_name = (mixer_get_ctl_by_name_fn)dlsym(handle, "mixer_get_ctl_by_name");
    p_mixer_ctl_get_num_values = (mixer_ctl_get_num_values_fn)dlsym(handle, "mixer_ctl_get_num_values");
    p_mixer_ctl_get_array = (mixer_ctl_get_array_fn)dlsym(handle, "mixer_ctl_get_array");
    p_mixer_ctl_set_array = (mixer_ctl_set_array_fn)dlsym(handle, "mixer_ctl_set_array");
    p_mixer_ctl_set_value = (mixer_ctl_set_value_fn)dlsym(handle, "mixer_ctl_set_value");

    if (!p_pcm_open || !p_pcm_is_ready || !p_pcm_get_error || !p_pcm_write || !p_pcm_close ||
        !p_mixer_open || !p_mixer_close || !p_mixer_get_ctl_by_name ||
        !p_mixer_ctl_get_num_values || !p_mixer_ctl_get_array ||
        !p_mixer_ctl_set_array || !p_mixer_ctl_set_value) {
        return -1;
    }
    return 0;
}

static void set_ctl_single(struct mixer *mx, const char *name, int val) {
    struct mixer_ctl *ctl = p_mixer_get_ctl_by_name(mx, name);
    if (!ctl) {
        return;
    }
    int rc = p_mixer_ctl_set_value(ctl, 0, val);
    if (rc != 0) {
        fprintf(stderr, "mixer_ctl_set_value(%s=%d) rc=%d\n", name, val, rc);
    }
}

static void set_voice_mute3(struct mixer *mx, const char *name, long mute, long ramp_ms) {
    struct mixer_ctl *ctl = p_mixer_get_ctl_by_name(mx, name);
    if (!ctl) {
        return;
    }
    long v1[3] = {mute, -1L, ramp_ms};
    int rc = p_mixer_ctl_set_array(ctl, v1, 3);
    if (rc != 0) {
        long v2[3] = {mute, (long)0xFFFFFFFFUL, ramp_ms};
        rc = p_mixer_ctl_set_array(ctl, v2, 3);
        if (rc != 0) {
            fprintf(stderr, "set_voice_mute3(%s, mute=%ld) failed rc=%d\n", name, mute, rc);
        }
    }
}

static void apply_call_mutes(struct mixer *mx) {
    set_voice_mute3(mx, "Voice Rx Device Mute", 1L, 20L);
    set_voice_mute3(mx, "Voice Tx Device Mute", 1L, 20L);
    set_voice_mute3(mx, "Voice Tx Mute", 0L, 20L);
}

static void restore_call_mutes(struct mixer *mx) {
    set_voice_mute3(mx, "Voice Rx Device Mute", 0L, 20L);
    set_voice_mute3(mx, "Voice Tx Device Mute", 0L, 20L);
    set_voice_mute3(mx, "Voice Tx Mute", 0L, 20L);

    set_ctl_single(mx, "Incall_Music Audio Mixer MultiMedia1", 0);
    set_ctl_single(mx, "Incall_Music Audio Mixer MultiMedia2", 0);
    set_ctl_single(mx, "Incall_Music Audio Mixer MultiMedia5", 0);
    set_ctl_single(mx, "Incall_Music Audio Mixer MultiMedia9", 0);
    set_ctl_single(mx, "Incall_Music_2 Audio Mixer MultiMedia1", 0);
    set_ctl_single(mx, "Incall_Music_2 Audio Mixer MultiMedia2", 0);
    set_ctl_single(mx, "Incall_Music_2 Audio Mixer MultiMedia5", 0);
    set_ctl_single(mx, "Incall_Music_2 Audio Mixer MultiMedia9", 0);
}

static void set_stream_app_type_cfg(struct mixer *mx, unsigned int dev, unsigned int rate) {
    char name[64];
    snprintf(name, sizeof(name), "Audio Stream %u App Type Cfg", dev);
    struct mixer_ctl *ctl = p_mixer_get_ctl_by_name(mx, name);
    if (!ctl) {
        return;
    }
    unsigned int n = p_mixer_ctl_get_num_values(ctl);
    if (n == 0 || n > 256) {
        return;
    }
    long arr[256];
    memset(arr, 0, sizeof(arr));
    (void)p_mixer_ctl_get_array(ctl, arr, n);
    arr[0] = 69936L; /* DEFAULT_APP_TYPE_RX_PATH */
    if (n > 1) {
        arr[1] = 7L; /* ACDB_ID_HANDSET_SPKR */
    }
    if (n > 2) {
        arr[2] = (long)rate;
    }
    int rc = p_mixer_ctl_set_array(ctl, arr, n);
    if (rc != 0 && n > 3) {
        rc = p_mixer_ctl_set_array(ctl, arr, 3);
    }
    if (rc != 0) {
        fprintf(stderr, "set_stream_app_type_cfg(%s) failed rc=%d\n", name, rc);
    }
}

static void set_playback_channel_map(struct mixer *mx, unsigned int dev, unsigned int channels) {
    char name[64];
    snprintf(name, sizeof(name), "Playback Channel Map%u", dev);
    struct mixer_ctl *ctl = p_mixer_get_ctl_by_name(mx, name);
    if (!ctl) {
        return;
    }
    unsigned int n = p_mixer_ctl_get_num_values(ctl);
    size_t wn = (n > 0 && n <= 16) ? (size_t)n : 8u;
    long chmap[16];
    memset(chmap, 0, sizeof(chmap));
    if (channels == 1) {
        chmap[0] = 3L; /* PCM_CHANNEL_FC */
    } else {
        chmap[0] = 1L; /* PCM_CHANNEL_FL */
        chmap[1] = 2L; /* PCM_CHANNEL_FR */
    }
    int rc = p_mixer_ctl_set_array(ctl, chmap, wn);
    if (rc != 0) {
        fprintf(stderr, "set_playback_channel_map(%s) failed rc=%d\n", name, rc);
    }
}

int main(int argc, char **argv) {
    int check_only = 0;
    int mute_call = 0;
    int unmute_call = 0;
    unsigned int card = 0;
    unsigned int device = 1;
    unsigned int rate = 48000;
    unsigned int channels = 1;
    unsigned int period_size = 240;
    unsigned int period_count = 2;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--check") == 0) {
            check_only = 1;
        } else if (strcmp(argv[i], "--mute-call") == 0) {
            mute_call = 1;
        } else if (strcmp(argv[i], "--unmute-call") == 0) {
            unmute_call = 1;
        } else if (strcmp(argv[i], "-D") == 0 && i + 1 < argc) {
            card = (unsigned int)strtoul(argv[++i], NULL, 10);
        } else if (strcmp(argv[i], "-d") == 0 && i + 1 < argc) {
            device = (unsigned int)strtoul(argv[++i], NULL, 10);
        } else if (strcmp(argv[i], "-r") == 0 && i + 1 < argc) {
            rate = (unsigned int)strtoul(argv[++i], NULL, 10);
        } else if (strcmp(argv[i], "-c") == 0 && i + 1 < argc) {
            channels = (unsigned int)strtoul(argv[++i], NULL, 10);
        } else if (strcmp(argv[i], "-p") == 0 && i + 1 < argc) {
            period_size = (unsigned int)strtoul(argv[++i], NULL, 10);
        } else if (strcmp(argv[i], "-n") == 0 && i + 1 < argc) {
            period_count = (unsigned int)strtoul(argv[++i], NULL, 10);
        }
    }

    void *handle = open_tinyalsa();
    if (!handle) {
        fprintf(stderr, "dlopen libtinyalsa.so failed: %s\n", dlerror());
        return 2;
    }

    if (resolve_symbols(handle) != 0) {
        fprintf(stderr, "dlsym libtinyalsa.so missing symbol\n");
        dlclose(handle);
        return 3;
    }

    if (check_only) {
        printf("OK tinyalsa\n");
        fflush(stdout);
        dlclose(handle);
        return 0;
    }

    if (mute_call || unmute_call) {
        struct mixer *mx = p_mixer_open(card);
        if (!mx) {
            fprintf(stderr, "mixer_open(card=%u) failed\n", card);
            dlclose(handle);
            return 4;
        }
        if (mute_call) {
            apply_call_mutes(mx);
            printf("OK mute-call\n");
        } else {
            restore_call_mutes(mx);
            printf("OK unmute-call\n");
        }
        fflush(stdout);
        p_mixer_close(mx);
        dlclose(handle);
        return 0;
    }

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    const char *incall_ctl = (device == 14 || device == 16)
                                 ? "Incall_Music_2 Audio Mixer MultiMedia9"
                                 : "Incall_Music Audio Mixer MultiMedia2";

    struct mixer *mx = p_mixer_open(card);
    if (mx != NULL) {
        if (device == 14 || device == 16) {
            set_ctl_single(mx, "PRI_MI2S_RX Audio Mixer MultiMedia9", 0);
            set_ctl_single(mx, "Incall_Music_2 Audio Mixer MultiMedia1", 0);
            set_ctl_single(mx, "Incall_Music_2 Audio Mixer MultiMedia2", 0);
            set_ctl_single(mx, "Incall_Music_2 Audio Mixer MultiMedia5", 0);
            set_ctl_single(mx, "Incall_Music_2 Audio Mixer MultiMedia9", 0);
        } else {
            set_ctl_single(mx, "PRI_MI2S_RX Audio Mixer MultiMedia2", 0);
            set_ctl_single(mx, "QUIN_MI2S_RX Audio Mixer MultiMedia2", 0);
            set_ctl_single(mx, "Incall_Music Audio Mixer MultiMedia1", 0);
            set_ctl_single(mx, "Incall_Music Audio Mixer MultiMedia5", 0);
            set_ctl_single(mx, "Incall_Music Audio Mixer MultiMedia9", 0);
            set_ctl_single(mx, "Incall_Music Audio Mixer MultiMedia2", 0);
        }
        /* Brief settle so fe_sessions bit & v->music_count clear to 0 before 0->1 enable */
        usleep(15000);
        set_stream_app_type_cfg(mx, device, rate);
        set_ctl_single(mx, incall_ctl, 1);
    }

    struct tiny_pcm_config cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.channels = channels;
    cfg.rate = rate;
    cfg.period_size = period_size;
    cfg.period_count = period_count;
    cfg.format = 0; /* PCM_FORMAT_S16_LE */
    cfg.start_threshold = (device == 1 && period_size == 240) ? 60u : period_size;
    cfg.stop_threshold = 2147483647u;
    cfg.silence_threshold = 0;
    cfg.silence_size = 0;
    cfg.avail_min = (device == 1 && period_size == 240) ? 60 : (int)period_size;

    struct pcm *pcm = p_pcm_open(card, device, 0 /* PCM_OUT */, &cfg);
    if (!pcm || !p_pcm_is_ready(pcm)) {
        unsigned int retry_pcnt = (period_count == 2) ? 4u : 2u;
        const char *first_err = pcm ? p_pcm_get_error(pcm) : "null";
        fprintf(stderr, "pcm_open(pcnt=%u) failed (%s), retrying pcnt=%u\n",
                period_count, first_err ? first_err : "unknown", retry_pcnt);
        if (pcm) {
            p_pcm_close(pcm);
        }
        cfg.period_count = retry_pcnt;
        pcm = p_pcm_open(card, device, 0 /* PCM_OUT */, &cfg);
    }

    if (!pcm || !p_pcm_is_ready(pcm)) {
        const char *err = pcm ? p_pcm_get_error(pcm) : "pcm_open returned null";
        fprintf(stderr, "pcm_open(card=%u dev=%u) failed: %s\n",
                card, device, err ? err : "unknown");
        if (pcm) {
            p_pcm_close(pcm);
        }
        if (mx != NULL) {
            set_ctl_single(mx, incall_ctl, 0);
            p_mixer_close(mx);
        }
        dlclose(handle);
        return 4;
    }

    if (p_pcm_prepare != NULL) {
        (void)p_pcm_prepare(pcm);
    }

    if (mx != NULL) {
        set_playback_channel_map(mx, device, cfg.channels);
        apply_call_mutes(mx);
        p_mixer_close(mx);
        mx = NULL;
    }

    size_t chunk_bytes = (size_t)cfg.period_size * (size_t)cfg.channels * 2u;
    uint8_t *buf = (uint8_t *)malloc(chunk_bytes);
    if (!buf) {
        fprintf(stderr, "malloc(%zu) failed\n", chunk_bytes);
        p_pcm_close(pcm);
        mx = p_mixer_open(card);
        if (mx != NULL) {
            set_ctl_single(mx, incall_ctl, 0);
            p_mixer_close(mx);
        }
        dlclose(handle);
        return 5;
    }

    printf("READY card=%u dev=%u rate=%u ch=%u psize=%u pcnt=%u\n",
           card, device, cfg.rate, cfg.channels, cfg.period_size, cfg.period_count);
    fflush(stdout);

    int write_err_logged = 0;
    while (!g_stop) {
        size_t filled = 0;
        while (filled < chunk_bytes && !g_stop) {
            ssize_t r = read(STDIN_FILENO, buf + filled, chunk_bytes - filled);
            if (r == 0) {
                /* EOF */
                g_stop = 1;
                break;
            }
            if (r < 0) {
                if (errno == EINTR) {
                    continue;
                }
                g_stop = 1;
                break;
            }
            filled += (size_t)r;
        }
        if (filled == 0) {
            break;
        }
        if (filled < chunk_bytes) {
            memset(buf + filled, 0, chunk_bytes - filled);
        }
        int rc = p_pcm_write(pcm, buf, (unsigned int)chunk_bytes);
        if (rc != 0 && !write_err_logged) {
            fprintf(stderr, "pcm_write warning rc=%d (%s)\n", rc, p_pcm_get_error(pcm));
            write_err_logged = 1;
        }
    }

    free(buf);
    p_pcm_close(pcm);

    mx = p_mixer_open(card);
    if (mx != NULL) {
        set_ctl_single(mx, incall_ctl, 0);
        p_mixer_close(mx);
    }

    dlclose(handle);
    return 0;
}
