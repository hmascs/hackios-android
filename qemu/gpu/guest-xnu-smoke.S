/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Standalone ARM64 Mach-O probe for a disposable XNU guest. Uses a single
 * fixed address forwarded only to the host renderer (no iOS framework link). */
.text
.globl _start
.p2align 2
_start:
    mov x0, #2            /* AF_INET */
    mov x1, #1            /* SOCK_STREAM */
    mov x2, #0
    mov x16, #97          /* socket */
    svc #0x80
    b.cs bad_socket
    mov x19, x0
    adr x1, address
    mov x2, #16
    mov x16, #98          /* connect */
    svc #0x80
    b.cs bad_connect

    adr x20, command
    mov x21, #48
    bl send_all
    adr x20, expected
    sub sp, sp, #16
    mov x22, sp
    mov x21, #16
receive:
    mov x0, x19
    mov x1, x22
    mov x2, x21
    mov x16, #3           /* read */
    svc #0x80
    b.cs bad_transfer
    cbz x0, bad_transfer
    add x22, x22, x0
    subs x21, x21, x0
    b.ne receive
    ldp x2, x3, [sp]
    ldp x4, x5, [x20]
    cmp x2, x4
    ccmp x3, x5, #0, eq
    b.ne bad_pixels
    /* The host may stop QEMU immediately after HGOK. Log first. */
    mov x0, #1
    adr x1, success
    mov x2, #37
    mov x16, #4
    svc #0x80
    adr x20, ack
    mov x21, #4
    bl send_all
    mov x0, #0
    b terminate
bad_socket:
    adr x1, socket_error
    mov x2, #24
    b fail
bad_connect:
    adr x1, connect_error
    mov x2, #25
    b fail
bad_transfer:
    adr x1, transfer_error
    mov x2, #26
    b fail
bad_pixels:
    adr x1, pixel_error
    mov x2, #23
fail:
    mov x20, #1
report:
    mov x0, #1
    mov x16, #4           /* write */
    svc #0x80
    mov x0, x20
terminate:
    mov x16, #1           /* exit */
    svc #0x80
    brk #1

send_all:
    mov x0, x19
    mov x1, x20
    mov x2, x21
    mov x16, #4
    svc #0x80
    b.cs bad_transfer
    cbz x0, bad_transfer
    add x20, x20, x0
    subs x21, x21, x0
    b.ne send_all
    ret

.p2align 3
address:
    .byte 16, 2, 0x4c, 0x1b, 10, 0, 2, 100, 0, 0, 0, 0, 0, 0, 0, 0 /* :19483 */
command:
    .ascii "HGL1"
    .short 1, 48
    .float -0.8, -0.8, 0.8, -0.8, 0.0, 0.8
    .float 1.0, 0.0, 1.0, 1.0
expected:
    .ascii "HGR1"
    .long 0
    .byte 255, 0, 255, 255, 0, 0, 0, 255
ack: .ascii "HGOK"
success: .ascii "hackios-gpu: XNU verified GPU pixels\n"
socket_error: .ascii "hackios-gpu: socket err\n"
connect_error: .ascii "hackios-gpu: connect err\n"
transfer_error: .ascii "hackios-gpu: transfer err\n"
pixel_error: .ascii "hackios-gpu: pixel err\n"
