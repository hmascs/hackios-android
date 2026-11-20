/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Experimental single-context replacement for Redwood's gl_osmesa.c.
 * Link only in an explicitly selected remote build, never alongside OSMesa. */
#include "eagl-guest-client.h"

extern int socket(int, int, int);
extern int connect(int, const void *, unsigned);
extern int setsockopt(int, int, int, const void *, unsigned);
extern int close(int);
extern int getpid(void);
extern int write(int, const void *, size_t);
extern int snprintf(char *, size_t, const char *, ...);
extern int *__error(void);
extern unsigned sleep(unsigned);
extern int sysctlbyname(const char *, void *, size_t *, const void *, size_t);
extern int getsockopt(int,int,int,void *,unsigned *);
#include <string.h>

static int boot_flag(const char *args,const char *flag)
{
    size_t n=strlen(flag);
    while (*args) {
        while (*args==' ' || *args=='\t') ++args;
        const char *end=args;
        while (*end && *end!=' ' && *end!='\t') ++end;
        if ((size_t)(end-args)==n && !memcmp(args,flag,n)) return 1;
        args=end;
    }
    return 0;
}

struct redwood_gl { struct hios_eagl_client client; };
static struct redwood_gl remote;
static _Thread_local struct redwood_gl *bound;
static unsigned reserved;

struct redwood_gl *redwood_gl_create(int version)
{
    /* A QEMU chardev currently serves one stream. Reject another context,
     * rather than restarting sequence numbers on the existing stream. */
    if (version != 3 || __atomic_exchange_n(&reserved, 1, __ATOMIC_ACQ_REL)) return NULL;
    int fd = -1, err = 0;
    const char *stage = "socket";
    const int one = 1;
    const struct { long seconds; int micros; } timeout = {10, 0};
    const unsigned char address[16] = {16, 2, 0x4c, 0x1c, 10, 0, 2, 100}; /* 19484 */
    char arguments[4096]={0};size_t arguments_size=sizeof arguments;
    int bootargs_ok=sysctlbyname("kern.bootargs",arguments,&arguments_size,NULL,0)==0;
    arguments[sizeof arguments-1]=0;
    const int batch_requested=bootargs_ok && boot_flag(arguments,"glbatch=1");
    const int nodelay_requested=bootargs_ok && boot_flag(arguments,"glnodelay=1");
    int nodelay_set=0,nodelay_get=0,nodelay_error=0;
    /* launchd starts the compositor before the virtio DHCP job completes.
     * Retry only failures that prove there was no route, before any EGL bytes. */
    for (unsigned attempt = 0; attempt < 45; ++attempt) {
        stage = "socket";
        fd = socket(2, 1, 0); /* Darwin AF_INET, SOCK_STREAM */
        if (fd < 0) { err = *__error(); goto fail; }
        stage = "setsockopt";
        if (setsockopt(fd, 0xffff, 0x1022, &one, sizeof one) || /* SO_NOSIGPIPE */
            setsockopt(fd, 0xffff, 0x1005, &timeout, sizeof timeout) || /* SO_SNDTIMEO */
            setsockopt(fd, 0xffff, 0x1006, &timeout, sizeof timeout)) { err = *__error(); goto fail; }
        stage = "connect";
        if (!connect(fd, address, sizeof address)) break;
        err = *__error();
        if ((err != 49 && err != 51 && err != 65) || attempt == 44) goto fail;
        close(fd);
        fd = -1;
        sleep(1);
    }
    if (nodelay_requested) {
        /* Darwin IPPROTO_TCP=6/TCP_NODELAY=1. This is the guest TCP socket,
         * never the QEMU->worker Unix-domain connection. */
        nodelay_set=setsockopt(fd,6,1,&one,sizeof one)==0;
        if (!nodelay_set) nodelay_error=*__error();
        unsigned option_bytes=sizeof nodelay_get;
        if (getsockopt(fd,6,1,&nodelay_get,&option_bytes) || option_bytes!=sizeof nodelay_get) {
            if (!nodelay_error) nodelay_error=*__error();
            nodelay_get=-1;
        }
    }
    stage = "EA_OPEN";
    err = 0;
    remote.client = (struct hios_eagl_client){.fd = fd, .batch_requested=batch_requested};
    unsigned char request[4];
    hios_put32(request, 3);
    uint32_t status;
    size_t received;
    if (!hios_eagl_call(&remote.client, 1, EA_OPEN, request, sizeof request, NULL, 0, &status, &received) ||
        status != EA_OK || received) goto fail;
    char line[256];
    int n = snprintf(line, sizeof line, "hackios-eagl: guest pid=%d context=1 remote opened bootargs_ok=%d glbatch_requested=%d glnodelay_requested=%d nodelay_set=%d nodelay_get=%d nodelay_errno=%d\n", getpid(),bootargs_ok,batch_requested,nodelay_requested,nodelay_set,nodelay_get,nodelay_error);
    if (n > 0 && (size_t)n < sizeof line) (void)write(2, line, (size_t)n);
    return &remote;
fail:
    n = snprintf(line, sizeof line, "hackios-eagl: guest pid=%d init failed at %s errno=%d\n", getpid(), stage, err);
    if (n > 0 && (size_t)n < sizeof line) (void)write(2, line, (size_t)n);
    if (fd >= 0) close(fd);
    return NULL;
}

/* CoreAnimation initializes on one thread and renders on another. The
 * host stays on its worker EGL thread; guest bindings share this context. */
int redwood_gl_make_current(struct redwood_gl *gl)
{
    if (gl) {
        hios_eagl_lock();
        int valid = gl == &remote && gl->client.fd >= 0 && !gl->client.failed;
        hios_eagl_unlock();
        if (!valid) return 0;
    }
    if (bound != gl) {
        if (!hios_eagl_bind_thread(gl ? &gl->client : NULL, gl ? 1 : 0) && gl) return 0;
        bound = gl;
    }
    return 1;
}

void redwood_gl_destroy(struct redwood_gl *gl)
{
    if (gl != &remote) return;
    redwood_gl_make_current(NULL);
    hios_eagl_lock();
    if (gl->client.fd < 0) { hios_eagl_unlock(); return; }
    uint32_t status;
    size_t received;
    if (hios_eagl_call(&gl->client, 1, EA_CLOSE, NULL, 0, NULL, 0, &status, &received) && status == EA_OK)
        (void)hios_eagl_call(&gl->client, 0, EA_END_SESSION, NULL, 0, NULL, 0, &status, &received);
    close(gl->client.fd);
    gl->client.fd = -1;
    hios_eagl_unlock();
}

const char *redwood_gl_library(void) { return "HackiOS remote GLES (experimental)"; }
int redwood_gl_surface_texture(struct redwood_gl *gl, uint32_t surface)
{ (void)gl; (void)surface; return 0; } /* no IOSurface zero-copy across VM */
