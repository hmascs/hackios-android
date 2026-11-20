/* Experimental HTB1 batching, isolated copy only. No native GL calls here. */
#ifndef HIOS_EAGL_BATCH_H
#define HIOS_EAGL_BATCH_H
#include "eagl-wire.h"
#include "eagl-client-arrays.h"
#define HIOS_BATCH_TAG UINT32_C(0x31425448) /* HTB1 */
#define HIOS_BATCH_BYTES 65536u
#define HIOS_BATCH_COMMANDS 64u
#define HIOS_BATCH_FEATURES 1u
static inline int hios_batchable(uint32_t op)
{
    switch (op) {
#define GL_VOID(id,name,n,...) case id: return op != EA_glFlush && op != EA_glFinish && op != EA_glTexParameteri;
#define GL_UINT(id,name,n,...)
#include "eagl-scalars.def"
#undef GL_VOID
#undef GL_UINT
    case EA_UNIFORM_VECTOR: case EA_DRAW_ARRAYS: case EA_DRAW_NO_ARRAYS: return 1;
    default: return 0;
    }
}
static inline int hios_batch_command_valid(uint32_t op, const unsigned char *data, size_t bytes)
{
    if (!hios_batchable(op) || (!data && bytes)) return 0;
    switch (op) {
#define GL_VOID(id,name,n,...) case id: return bytes == 4u*(n);
#define GL_UINT(id,name,n,...)
#include "eagl-scalars.def"
#undef GL_VOID
#undef GL_UINT
    case EA_UNIFORM_VECTOR: {
        if (bytes < 12) return 0;
        uint32_t count=hios_get32(data+4), arity=hios_get32(data+8);
        return count<=64 && (arity>=1 && (arity<=4 || arity==16)) && bytes==12u+(size_t)count*arity*4;
    }
    case EA_DRAW_NO_ARRAYS:
        return bytes==12 && hios_get32(data)<=6 && hios_get32(data+8)>0 &&
            hios_get32(data+8)<=65536 && hios_get32(data+4)<=INT32_MAX-(hios_get32(data+8)-1);
    case EA_DRAW_ARRAYS: {
        struct hios_array_draw draw;
        return hios_array_decode(data,bytes,&draw);
    }
    default: return 0;
    }
}
static inline int hios_batch_packet_valid(const unsigned char *data,size_t bytes)
{
    if (!data || bytes<8 || bytes>HIOS_BATCH_BYTES || hios_get32(data)!=HIOS_BATCH_TAG) return 0;
    uint32_t count=hios_get32(data+4);
    if (!count || count>HIOS_BATCH_COMMANDS) return 0;
    size_t at=8;
    for(uint32_t n=0;n<count;n++) {
        if(bytes-at<8) return 0;
        uint32_t op=hios_get32(data+at),len=hios_get32(data+at+4);
        at+=8;
        if(len>bytes-at || !hios_batch_command_valid(op,data+at,len)) return 0;
        at+=len;
    }
    return at==bytes;
}
#endif
