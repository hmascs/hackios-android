/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _DEFAULT_SOURCE
#include "eagl-client-arrays.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static unsigned index_at(const unsigned char *p, unsigned i)
{
    return p[2*i] | (unsigned)p[2*i+1] << 8;
}

static void zero_prefix_and_original_indices(void)
{
    float positions[10][2];
    for (unsigned i=0; i<10; ++i) { positions[i][0]=(float)i; positions[i][1]=(float)-i; }
    struct hios_client_array array = {0,2,0x1406,0,positions,sizeof positions,sizeof positions[0]};
    unsigned char sequential[256], indexed[256];
    size_t n=999,m=999;
    uint16_t indices[]={7,8,9};
    assert(hios_array_encode_sequential(sequential,sizeof sequential,&n,4,7,3,&array,1));
    assert(hios_array_encode(indexed,sizeof indexed,&m,4,3,0x1403,indices,sizeof indices,&array,1));
    assert(n==130 && n==m && !memcmp(sequential,indexed,n));
    struct hios_array_draw draw;
    assert(hios_array_decode(sequential,n,&draw));
    assert(draw.vertex_count==10 && draw.count==3);
    for (unsigned i=0; i<3; ++i) assert(index_at(draw.indices,i)==7+i);
    const unsigned char *data=draw.arrays[0].data;
    for (unsigned i=0; i<7*sizeof positions[0]; ++i) assert(data[i]==0);
    assert(!memcmp(data+7*sizeof positions[0],positions+7,3*sizeof positions[0]));
    memset(indexed,0xa5,sizeof indexed);
    assert(!hios_array_encode_sequential(indexed,n-1,&m,4,7,3,&array,1) && m==0);
    for (size_t i=0; i<sizeof indexed; ++i) assert(indexed[i]==0xa5);
    assert(!hios_array_encode_sequential(indexed,sizeof indexed,&m,4,7,0,&array,1) && m==0);
    assert(!hios_array_encode_sequential(indexed,sizeof indexed,&m,4,UINT32_MAX,1,&array,1) && m==0);
    assert(!hios_array_encode_sequential(indexed,sizeof indexed,&m,4,0,UINT32_MAX,&array,1) && m==0);
    assert(!hios_array_encode(indexed,sizeof indexed,&m,4,3,0x1406,indices,sizeof indices,&array,1) && m==0);
    assert(!hios_array_encode(indexed,sizeof indexed,&m,4,3,0x1403,indices,sizeof indices-1,&array,1) && m==0);
    array.bytes--;
    assert(!hios_array_encode_sequential(indexed,sizeof indexed,&m,4,7,3,&array,1) && m==0);
}

static void unreadable_prefix_is_never_read(void)
{
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    assert(page && page%8==0 && page/8+3<65536);
    unsigned char *source=mmap(NULL,2*page,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(source!=MAP_FAILED && mprotect(source+page,page,PROT_READ|PROT_WRITE)==0);
    float positions[3][2]={{-.8f,-.8f},{.8f,-.8f},{0,.8f}};
    memcpy(source+page,positions,sizeof positions);
    struct hios_client_array array={0,2,0x1406,0,source,2*page,8};
    unsigned char *packet=malloc(HIOS_ARRAY_LIMIT+32);
    assert(packet); memset(packet,0xa5,HIOS_ARRAY_LIMIT+32);
    size_t bytes=0;
    assert(hios_array_encode_sequential(packet+16,HIOS_ARRAY_LIMIT,&bytes,4,(uint32_t)(page/8),3,&array,1));
    struct hios_array_draw draw;
    assert(hios_array_decode(packet+16,bytes,&draw));
    for(size_t i=0;i<page;++i) assert(((const unsigned char *)draw.arrays[0].data)[i]==0);
    assert(!memcmp((const unsigned char *)draw.arrays[0].data+page,positions,sizeof positions));
    for(unsigned i=0;i<16;++i) assert(packet[i]==0xa5 && packet[HIOS_ARRAY_LIMIT+16+i]==0xa5);
    free(packet); assert(munmap(source,2*page)==0);
}

static void original_index_and_packet_budgets(void)
{
    unsigned char *source=malloc(65536), *packet=malloc(HIOS_ARRAY_LIMIT);
    assert(source && packet); memset(source,0x7b,65536);
    struct hios_client_array array={0,1,0x1401,0,source,65536,1};
    size_t bytes=0;
    assert(hios_array_encode_sequential(packet,HIOS_ARRAY_LIMIT,&bytes,0,65535,1,&array,1));
    struct hios_array_draw draw;
    assert(hios_array_decode(packet,bytes,&draw) && draw.vertex_count==65536);
    assert(index_at(draw.indices,0)==65535);
    const unsigned char *data=draw.arrays[0].data;
    for(unsigned i=0;i<65535;++i) assert(data[i]==0);
    assert(data[65535]==0x7b);
    assert(!hios_array_encode_sequential(packet,HIOS_ARRAY_LIMIT,&bytes,0,65536,1,&array,1) && bytes==0);
    uint32_t big=UINT32_MAX;
    assert(!hios_array_encode(packet,HIOS_ARRAY_LIMIT,&bytes,0,1,0x1405,&big,sizeof big,&array,1) && bytes==0);
    uint32_t limit=65536;
    assert(!hios_array_encode(packet,HIOS_ARRAY_LIMIT,&bytes,0,1,0x1405,&limit,sizeof limit,&array,1) && bytes==0);
    /* Budget failure must be decided before touching any source attribute. */
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    void *guard=mmap(NULL,page,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(guard!=MAP_FAILED);
    struct hios_client_array arrays[HIOS_ARRAY_SLOTS];
    for(unsigned i=0;i<HIOS_ARRAY_SLOTS;++i)
        arrays[i]=(struct hios_client_array){i,4,0x1406,0,guard,SIZE_MAX,16};
    uint32_t last=65535;
    assert(!hios_array_encode(packet,HIOS_ARRAY_LIMIT,&bytes,0,1,0x1405,&last,sizeof last,arrays,HIOS_ARRAY_SLOTS) && bytes==0);
    assert(munmap(guard,page)==0);
    free(packet); free(source);
}

int main(void)
{
    zero_prefix_and_original_indices();
    unreadable_prefix_is_never_read();
    original_index_and_packet_budgets();
    puts("HAA1 original indices: first7/index789, zero prefix, source guard, caps and output canaries passed");
    return 0;
}
