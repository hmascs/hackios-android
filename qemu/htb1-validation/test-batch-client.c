/* Isolated socket transport regression: no GPU/VM required. */
#define _GNU_SOURCE
#include "eagl-guest-client.h"
#include "eagl-batch.h"
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

struct server {
    int fd,legacy,partial_reply,fail_batch;
    unsigned requests,batches,executed,standalone_draws,queries,max_batch_bytes;
    uint32_t expected_seq;
    int value;
};
static int exact(int fd,void *bytes,size_t n,int write_it)
{
    unsigned char *p=bytes;
    while(n) {
        ssize_t got=write_it?write(fd,p,n):read(fd,p,n);
        if(got<0 && errno==EINTR)continue;
        if(got<=0)return 0;
        p+=got;n-=(size_t)got;
    }
    return 1;
}
static void execute(struct server *s,uint32_t op,const unsigned char *p,size_t bytes)
{
    assert(hios_batch_command_valid(op,p,bytes));
    if(op==EA_glUniform1i)s->value=(int)hios_get32(p+4);
    ++s->executed;
}
static void *serve(void *opaque)
{
    struct server *s=opaque;
    unsigned char header[20];
    while(exact(s->fd,header,sizeof header,0)) {
        assert(hios_get32(header)==HIOS_EAGL_REQUEST);
        uint32_t seq=hios_get32(header+4),op=hios_get32(header+8),len=hios_get32(header+16);
        assert(seq==++s->expected_seq);
        unsigned char *data=malloc(len?len:1);assert(data);
        if(!exact(s->fd,data,len,0)){free(data);break;}
        ++s->requests;
        uint32_t status=EA_OK,n=0;unsigned char reply[16]={0};
        if(op==EA_BATCH_CAPS) {
            assert(len==4 && hios_get32(data)==HIOS_BATCH_TAG);
            if(s->legacy)status=EA_BAD_COMMAND;
            else {hios_put32(reply,HIOS_BATCH_TAG);hios_put32(reply+4,HIOS_BATCH_BYTES);hios_put32(reply+8,HIOS_BATCH_COMMANDS);hios_put32(reply+12,HIOS_BATCH_FEATURES);n=16;}
        } else if(op==EA_BATCH_EXEC) {
            ++s->batches;if(len>s->max_batch_bytes)s->max_batch_bytes=len;
            if(!hios_batch_packet_valid(data,len) || s->fail_batch)status=EA_DEVICE_ERROR;
            else {
                uint32_t count=hios_get32(data+4);size_t at=8;
                for(uint32_t i=0;i<count;i++) {uint32_t child=hios_get32(data+at),size=hios_get32(data+at+4);at+=8;execute(s,child,data+at,size);at+=size;}
            }
        } else if(op==EA_INTEGER) {++s->queries;hios_put32(reply,(uint32_t)s->value);n=4;}
        else if(op==EA_glGetError) {hios_put32(reply,0);n=4;}
        else if(op==EA_CLOSE || op==EA_OPEN || op==EA_END_SESSION) { }
        else {if(op==EA_DRAW_ARRAYS)++s->standalone_draws;execute(s,op,data,len);}
        free(data);
        hios_put32(header,HIOS_EAGL_REPLY);hios_put32(header+4,seq);hios_put32(header+8,op);hios_put32(header+12,status);hios_put32(header+16,n);
        if(s->partial_reply && op==EA_BATCH_EXEC) {exact(s->fd,header,10,1);break;}
        if(!exact(s->fd,header,sizeof header,1) || !exact(s->fd,reply,n,1))break;
        if(status==EA_DEVICE_ERROR)break;
    }
    close(s->fd);return NULL;
}
static pthread_t start(struct server *s,struct hios_eagl_client *c,int enabled)
{
    int fd[2];assert(!socketpair(AF_UNIX,SOCK_STREAM,0,fd));s->fd=fd[1];
    *c=(struct hios_eagl_client){.fd=fd[0],.batch_requested=enabled};
    pthread_t t;assert(!pthread_create(&t,NULL,serve,s));return t;
}
static void stop(pthread_t t,struct hios_eagl_client *c)
{ close(c->fd);assert(!pthread_join(t,NULL)); }
static int command(struct hios_eagl_client *c,uint32_t slot,uint32_t op,const void *data,size_t len,void *reply,size_t cap)
{
    uint32_t status=99;size_t received=0;
    return hios_eagl_call(c,slot,op,data,len,reply,cap,&status,&received) && status==EA_OK && received==cap;
}
static void set(struct hios_eagl_client *c,uint32_t slot,int value)
{ unsigned char p[8];hios_put32(p,1);hios_put32(p+4,(uint32_t)value);assert(command(c,slot,EA_glUniform1i,p,sizeof p,NULL,0)); }
static int get(struct hios_eagl_client *c,uint32_t slot)
{ unsigned char p[4]={0},reply[4];assert(command(c,slot,EA_INTEGER,p,4,reply,4));return (int)hios_get32(reply); }
extern unsigned glGetError(void);
extern void glUniform1i(int,int);
struct handoff {struct hios_eagl_client *client;};
static void *thread_set(void *p)
{ struct handoff *h=p;hios_eagl_bind_thread(h->client,1);glUniform1i(1,31);return NULL; }
static void *thread_query(void *p)
{ struct handoff *h=p;hios_eagl_bind_thread(h->client,1);unsigned char req[4]={0},reply[4];size_t n=0;assert(hios_eagl_current_call(EA_INTEGER,req,4,reply,4,&n));assert(n==4 && hios_get32(reply)==31);hios_eagl_bind_thread(NULL,0);return NULL; }
int main(void)
{
    signal(SIGPIPE,SIG_IGN);
    uint32_t status=0;size_t n=0;
#ifndef HIOS_TEST_BASELINE
    assert(!hios_eagl_call(NULL,1,EA_glFlush,NULL,0,NULL,0,&status,&n));
#else
    (void)status;(void)n; /* baseline red targets missing batching, not old NULL dereference */
#endif
    hios_eagl_bind_thread(NULL,0);assert(glGetError()==0x502);
    for(int enabled=0;enabled<2;enabled++) {
        struct server s={0};struct hios_eagl_client c;pthread_t t=start(&s,&c,enabled);
        unsigned char p[8];hios_put32(p,1);hios_put32(p+4,7);
        assert(command(&c,1,EA_glUniform1i,p,8,NULL,0));hios_put32(p+4,99);
        assert(get(&c,1)==7); /* enqueue copied payload; query is a barrier */
        assert(command(&c,1,EA_CLOSE,NULL,0,NULL,0));
        assert(c.batch_count==0 && c.batch_bytes==0);
        stop(t,&c);assert(s.executed==1);assert(s.batches==(unsigned)enabled);
    }
    {
        struct server s={.legacy=1};struct hios_eagl_client c;pthread_t t=start(&s,&c,1);
        set(&c,1,5);set(&c,1,8);assert(get(&c,1)==8);
        unsigned char error[4];assert(command(&c,1,EA_glGetError,NULL,0,error,4));assert(hios_get32(error)==0);
        assert(c.batch_disabled && !c.failed && !c.batch_count);
        stop(t,&c);assert(s.batches==0 && s.requests==5 && s.expected_seq==5);
    }
    {
        struct server s={0};struct hios_eagl_client c;pthread_t t=start(&s,&c,1);
        for(int i=0;i<65;i++)set(&c,1,i);
        assert(get(&c,1)==64);stop(t,&c);assert(s.batches==2 && s.executed==65);
    }
    {
        struct server s={0};struct hios_eagl_client c;pthread_t t=start(&s,&c,1);
        set(&c,1,11);set(&c,2,22);assert(get(&c,2)==22);stop(t,&c);assert(s.batches==2);
    }
    {
        struct server s={0};struct hios_eagl_client c;pthread_t t=start(&s,&c,1);
        struct handoff h={&c};pthread_t a,b;
        assert(!pthread_create(&a,NULL,thread_set,&h));assert(!pthread_join(a,NULL));
        assert(!pthread_create(&b,NULL,thread_query,&h));assert(!pthread_join(b,NULL));
        stop(t,&c);assert(s.executed==1 && s.queries==1 && s.batches==1);
    }
    {
        struct server s={0};struct hios_eagl_client c;pthread_t t=start(&s,&c,1);
        unsigned char uniform[12+64*16*4]={0};
        hios_put32(uniform,1);hios_put32(uniform+4,64);hios_put32(uniform+8,16);
        for(unsigned i=0;i<17;i++)assert(command(&c,1,EA_UNIFORM_VECTOR,uniform,sizeof uniform,NULL,0));
        assert(get(&c,1)==0);stop(t,&c);
        assert(s.batches==2 && s.executed==17 && s.max_batch_bytes<=65536 && c.batch_reason_counts[1]==1);
    }
    {
        struct server s={0};struct hios_eagl_client c;pthread_t t=start(&s,&c,1);
        float *vertices=calloc(20000,2*sizeof(float));unsigned char *packet=malloc(HIOS_ARRAY_LIMIT);assert(vertices && packet);
        struct hios_client_array array={0,2,0x1406,0,vertices,20000u*2*sizeof(float),2*sizeof(float)};
        size_t bytes=0;assert(hios_array_encode_sequential(packet,HIOS_ARRAY_LIMIT,&bytes,4,0,20000,&array,1));assert(bytes>65536);
        set(&c,1,44);assert(command(&c,1,EA_DRAW_ARRAYS,packet,bytes,NULL,0));assert(get(&c,1)==44);
        stop(t,&c);assert(s.batches==1 && s.standalone_draws==1 && s.executed==2);
        free(packet);free(vertices);
    }
    {
        struct server a={0},b={0};struct hios_eagl_client ca,cb;
        pthread_t ta=start(&a,&ca,1),tb=start(&b,&cb,1);
        hios_eagl_bind_thread(&ca,1);glUniform1i(1,51);
        hios_eagl_bind_thread(&cb,1);glUniform1i(1,62);
        assert(get(&ca,1)==51 && get(&cb,1)==62);
        hios_eagl_bind_thread(NULL,0);stop(ta,&ca);stop(tb,&cb);
        assert(a.executed==1 && b.executed==1 && a.batches==1 && b.batches==1);
    }
    for(int failure=0;failure<3;failure++) {
        struct server s={.partial_reply=failure==0,.fail_batch=failure==1};
        struct hios_eagl_client c;pthread_t t=start(&s,&c,1);set(&c,1,9);
        if(failure==2)shutdown(c.fd,SHUT_RDWR); /* cancellation before drain */
        unsigned char query[4]={0},reply[4];
        assert(!command(&c,1,EA_INTEGER,query,4,reply,4));
        assert(c.failed && !c.batch_count && !c.batch_bytes);
        assert(!command(&c,1,EA_INTEGER,query,4,reply,4)); /* no replay after poison */
        stop(t,&c);assert(s.queries==0 && s.batches==(failure==2?0u:1u));
    }
    {
        /* Pure host packet validator: valid first child plus malformed second
         * child fails before any executor is invoked. */
        unsigned char packet[48]={0};hios_put32(packet,HIOS_BATCH_TAG);hios_put32(packet+4,2);
        hios_put32(packet+8,EA_glUniform1i);hios_put32(packet+12,8);hios_put32(packet+16,1);hios_put32(packet+20,9);
        hios_put32(packet+24,EA_glViewport);hios_put32(packet+28,4);
        assert(!hios_batch_packet_valid(packet,36));
        hios_put32(packet+4,65);assert(!hios_batch_packet_valid(packet,24));
        hios_put32(packet+4,1);assert(hios_batch_packet_valid(packet,24));
    }
    puts("HTB1 CPU: off/on, deep copy, barrier, fallback sequence, caps, contexts, threads, partial I/O, cancel and poison passed");
    return 0;
}
