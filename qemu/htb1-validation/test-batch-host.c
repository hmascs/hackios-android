/* Actual worker dispatch, with Linux software EGL only. */
#define main hios_worker_main_for_test
#include "source/eagl-host-session.c"
#undef main
#include <assert.h>
int main(void)
{
    struct worker w={.expected="llvmpipe"};
    assert(init_display(&w));assert(open_context(&w,1));
    unsigned char small[2048],request[64]={0};unsigned char *out=small;size_t len=0;
    hios_put32(request,HIOS_BATCH_TAG);
    assert(dispatch(&w,1,EA_BATCH_CAPS,request,4,small,&out,&len)==EA_OK && len==16);
    glClearColor(.25f,.5f,.75f,1.f);
    assert(glGetError()==GL_NO_ERROR);
    hios_put32(request,HIOS_BATCH_TAG);hios_put32(request+4,2);
    hios_put32(request+8,EA_glClearColor);hios_put32(request+12,16);
    hios_put32(request+16,0x3f800000);hios_put32(request+20,0);hios_put32(request+24,0);hios_put32(request+28,0x3f800000);
    hios_put32(request+32,EA_glViewport);hios_put32(request+36,4);hios_put32(request+40,0);
    len=0;out=small;
    assert(dispatch(&w,1,EA_BATCH_EXEC,request,44,small,&out,&len)==EA_DEVICE_ERROR);
    GLfloat color[4]={0};glGetFloatv(GL_COLOR_CLEAR_VALUE,color);
    assert(color[0]==.25f && color[1]==.5f && color[2]==.75f && color[3]==1.f);
    /* Well-shaped native GL error must stay queued rather than be consumed by
     * batch execution: earlier valid GL state executes in original order. */
    hios_put32(request+32,EA_glEnable);hios_put32(request+36,4);hios_put32(request+40,0xdead);
    len=0;out=small;
    assert(dispatch(&w,1,EA_BATCH_EXEC,request,44,small,&out,&len)==EA_OK && !len);
    glGetFloatv(GL_COLOR_CLEAR_VALUE,color);assert(color[0]==1.f && color[1]==0 && color[2]==0 && color[3]==1.f);
    len=0;out=small;
    assert(dispatch(&w,1,EA_glGetError,NULL,0,small,&out,&len)==EA_OK && len==4 && hios_get32(out)==GL_INVALID_ENUM);
    assert(glGetError()==GL_NO_ERROR);
    assert(close_context(&w,1));eglTerminate(w.display);
    puts("HTB1 actual host dispatch: malformed second child executes none; native GL errors preserved");
    return 0;
}
