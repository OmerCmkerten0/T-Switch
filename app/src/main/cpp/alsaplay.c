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

typedef struct pcm *(*pcm_open_fn)(unsigned int card, unsigned int device,
                                   unsigned int flags,
                                   const struct tiny_pcm_config *config);
typedef int (*pcm_is_ready_fn)(const struct pcm *pcm);
typedef const char *(*pcm_get_error_fn)(const struct pcm *pcm);
typedef int (*pcm_write_fn)(struct pcm *pcm, const void *data, unsigned int count);
typedef int (*pcm_close_fn)(struct pcm *pcm);

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

int main(int argc, char **argv) {
    int check_only = 0;
    unsigned int card = 0;
    unsigned int device = 1;
    unsigned int rate = 48000;
    unsigned int channels = 1;
    unsigned int period_size = 240;
    unsigned int period_count = 4;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--check") == 0) {
            check_only = 1;
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

    pcm_open_fn p_open = (pcm_open_fn)dlsym(handle, "pcm_open");
    pcm_is_ready_fn p_is_ready = (pcm_is_ready_fn)dlsym(handle, "pcm_is_ready");
    pcm_get_error_fn p_get_error = (pcm_get_error_fn)dlsym(handle, "pcm_get_error");
    pcm_write_fn p_write = (pcm_write_fn)dlsym(handle, "pcm_write");
    pcm_close_fn p_close = (pcm_close_fn)dlsym(handle, "pcm_close");

    if (!p_open || !p_is_ready || !p_get_error || !p_write || !p_close) {
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

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    struct tiny_pcm_config cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.channels = channels;
    cfg.rate = rate;
    cfg.period_size = period_size;
    cfg.period_count = period_count;
    cfg.format = 0; /* PCM_FORMAT_S16_LE */
    cfg.start_threshold = period_size;
    cfg.stop_threshold = 2147483647u;
    cfg.silence_threshold = 0;
    cfg.silence_size = 0;
    cfg.avail_min = (int)period_size;

    struct pcm *pcm = p_open(card, device, 0 /* PCM_OUT */, &cfg);
    if ((!pcm || !p_is_ready(pcm)) && period_count != 2) {
        const char *first_err = pcm ? p_get_error(pcm) : "null";
        fprintf(stderr, "pcm_open(pcnt=%u) failed (%s), retrying pcnt=2\n",
                period_count, first_err ? first_err : "unknown");
        if (pcm) p_close(pcm);
        cfg.period_count = 2;
        pcm = p_open(card, device, 0 /* PCM_OUT */, &cfg);
    }

    if (!pcm || !p_is_ready(pcm)) {
        const char *err = pcm ? p_get_error(pcm) : "pcm_open returned null";
        fprintf(stderr, "pcm_open(card=%u dev=%u) failed: %s\n",
                card, device, err ? err : "unknown");
        if (pcm) p_close(pcm);
        dlclose(handle);
        return 4;
    }

    size_t chunk_bytes = (size_t)cfg.period_size * (size_t)cfg.channels * 2u;
    uint8_t *buf = (uint8_t *)malloc(chunk_bytes);
    if (!buf) {
        fprintf(stderr, "malloc(%zu) failed\n", chunk_bytes);
        p_close(pcm);
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
                if (errno == EINTR) continue;
                g_stop = 1;
                break;
            }
            filled += (size_t)r;
        }
        if (filled == 0) break;
        if (filled < chunk_bytes) {
            memset(buf + filled, 0, chunk_bytes - filled);
        }
        int rc = p_write(pcm, buf, (unsigned int)chunk_bytes);
        if (rc != 0 && !write_err_logged) {
            fprintf(stderr, "pcm_write warning rc=%d (%s)\n", rc, p_get_error(pcm));
            write_err_logged = 1;
        }
    }

    free(buf);
    p_close(pcm);
    dlclose(handle);
    return 0;
}
