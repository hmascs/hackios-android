/* Real experimental C client, not Python-built batch packets. */
#include "source/eagl-guest-client.h"
#include "source/eagl-batch.h"
#include <string.h>
static struct hios_eagl_client client;
void bridge_init(int fd,int batch)
{
    client=(struct hios_eagl_client){.fd=fd,.batch_requested=batch};
}
int bridge_command(uint32_t context,uint32_t op,const void *data,size_t bytes,
                   void *reply,size_t capacity,uint32_t *status,size_t *received)
{
    hios_eagl_lock();
    int ok=hios_eagl_call(&client,context,op,data,bytes,reply,hios_batchable(op)?0:capacity,status,received);
    hios_eagl_unlock();
    return ok;
}
void bridge_stats(uint64_t out[8])
{
    hios_eagl_lock();
    out[0]=client.seq;out[1]=client.batch_commands;out[2]=client.batch_drains;
    out[3]=client.batch_queued_bytes;out[4]=client.batch_flushed_bytes;out[5]=client.batch_drain_us;
    out[6]=(uint64_t)client.failed;out[7]=client.batch_count;
    hios_eagl_unlock();
}
