/* Test harness for QEMU's ppm_save; compiles both unchanged production function bodies. */
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <pixman.h>

#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#define PIXMAN_BE_r8g8b8 PIXMAN_b8g8r8
#else
#define PIXMAN_BE_r8g8b8 PIXMAN_r8g8b8
#endif
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#define OBJECT(x) (x)
#define QIO_CHANNEL(x) (x)
#define trace_ppm_save(fd, image) ((void)0)
#define g_strdup_printf msg
#define g_autoptr(t) t * __attribute__((cleanup(drop_##t)))
#define g_autofree __attribute__((cleanup(drop_string)))
typedef int Object;
typedef int Error;
static int writes;
static void drop_Object(Object **p) { if (*p) { close(**p); free(*p); } }
static void drop_string(char **p) { free(*p); }
static void drop_pixman_image_t(pixman_image_t **p) { if (*p) pixman_image_unref(*p); }
static char *msg(const char *fmt, int w, int h, int max) {
    char *buf = malloc(128);
    assert(buf && snprintf(buf, 128, fmt, w, h, max) > 0);
    return buf;
}
static Object *qio_channel_file_new_fd(int fd) {
    int *obj = malloc(sizeof(*obj));
    assert(obj);
    *obj = fd;
    return obj;
}
static void error_setg(Error **errp, const char *msg) { (void)errp; fprintf(stderr, "%s\n", msg); }
static int qio_channel_write_all(Object *o, const char *data, size_t size, Error **errp) {
    (void)errp;
    ++writes;
    while (size) {
        ssize_t n = write(*o, data, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        data += n;
        size -= n;
    }
    return 0;
}
static pixman_image_t *qemu_pixman_linebuf_create(pixman_format_code_t format, int width) {
    pixman_image_t *img = pixman_image_create_bits(format, width, 1, NULL, 0);
    assert(img);
    return img;
}
static void qemu_pixman_linebuf_fill(pixman_image_t *line, pixman_image_t *src, int w, int x, int y) {
    pixman_image_composite(PIXMAN_OP_SRC, src, NULL, line, x, y, 0, 0, 0, 0, w, 1);
}
#include "stock.h"
#include "patched.h"

typedef bool (*PpmSave)(int, pixman_image_t *, Error **);
static double now_ms(void) {
    struct timespec value;
    assert(clock_gettime(CLOCK_MONOTONIC, &value) == 0);
    return value.tv_sec * 1000.0 + value.tv_nsec / 1000000.0;
}
static int order(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return (x > y) - (x < y);
}
static double benchmark(PpmSave save, pixman_image_t *img, bool compact) {
    const int w = pixman_image_get_width(img), h = pixman_image_get_height(img);
    const size_t row = w * 3, stride = compact ? row : ((row + 3) & ~(size_t)3);
    unsigned char *result = malloc(stride);
    const uint32_t *src = pixman_image_get_data(img);
    double timing[15];
    assert(result);
    for (int t = 0; t < 15; t++) {
        FILE *f = tmpfile();
        assert(f);
        writes = 0;
        double start = now_ms();
        assert(save(dup(fileno(f)), img, NULL));
        timing[t] = now_ms() - start;
        if (t == 0) {
            assert(writes == 1 + (compact ? (h + 31) / 32 : h));
            rewind(f);
            char actual[128], expected[128];
            snprintf(expected, sizeof(expected), "P6\n%d %d\n255\n", w, h);
            assert(fread(actual, 1, strlen(expected), f) == strlen(expected));
            assert(!memcmp(actual, expected, strlen(expected)));
            for (int y = 0; y < h; y++) {
                assert(fread(result, 1, stride, f) == stride);
                for (int x = 0; x < w; x++) {
                    uint32_t p = src[y * w + x];
                    assert(result[3*x] == ((p >> 16) & 255));
                    assert(result[3*x+1] == ((p >> 8) & 255));
                    assert(result[3*x+2] == (p & 255));
                }
            }
            assert(fgetc(f) == EOF);
        }
        fclose(f);
    }
    free(result);
    qsort(timing, 15, sizeof(*timing), order);
    return timing[7];
}
int main(void) {
    const int widths[] = { 16, 1125, 1128 }, heights[] = { 3, 2436, 2436 };
    for (int i = 0; i < 3; i++) {
        int w = widths[i], h = heights[i];
        pixman_image_t *img = pixman_image_create_bits(PIXMAN_x8r8g8b8, w, h, NULL, 0);
        assert(img);
        uint32_t *src = pixman_image_get_data(img);
        for (int j = 0; j < w * h; j++) src[j] = (uint32_t)j * 38197u;
        double stock_ms = benchmark(stock, img, false);
        double patched_ms = benchmark(patched, img, true);
        printf("%dx%d verified | QIO writes %d -> %d | median C encoder %.3f -> %.3f ms\n",
               w, h, 1 + h, 1 + (h + 31) / 32, stock_ms, patched_ms);
        pixman_image_unref(img);
    }
}
