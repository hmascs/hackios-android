/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Read-only capacity and sector-read probe for the disposable XNU guest. */
.text
.globl _start
.p2align 2
_start:
    sub sp, sp, #32
    adr x0, path
    mov x1, #0        /* O_RDONLY */
    mov x2, #0
    mov x16, #5       /* open */
    svc #0x80
    b.cs fail
    mov x20, x0
    mov w1, #0x6418
    movk w1, #0x4004, lsl #16 /* DKIOCGETBLOCKSIZE */
    add x2, sp, #16
    mov x16, #54      /* ioctl */
    svc #0x80
    b.cs close_disk
    mov x0, x20
    mov w1, #0x6419
    movk w1, #0x4008, lsl #16 /* DKIOCGETBLOCKCOUNT */
    add x2, sp, #24
    mov x16, #54
    svc #0x80
    b.cs close_disk
    ldr w1, [sp, #16]
    ldr x2, [sp, #24]
    mul x1, x1, x2
    mov x2, #0x200000000 /* 8 GiB */
    cmp x1, x2
    b.ne close_disk
    sub sp, sp, #512
    mov x0, x20
    mov x1, sp
    mov x2, #512
    mov x16, #3       /* read one complete sector through the XNU driver */
    svc #0x80
    b.cs close_disk
    cmp x0, #512
    b.eq found
close_disk:
    mov x0, x20
    mov x16, #6       /* close */
    svc #0x80
fail:
    adr x1, not_found
    adr x2, not_found_end
    sub x2, x2, x1
    mov x20, #1
    b report
found:
    adr x1, success
    adr x2, success_end
    sub x2, x2, x1
    mov x20, #0
report:
    mov x0, #1
    mov x16, #4       /* write */
    svc #0x80
    mov x0, x20
    mov x16, #1       /* exit */
    svc #0x80
    brk #1

.p2align 3
path: .asciz "/dev/rrvblk0"
.balign 4
success: .ascii "hackios-disk: XNU read sector from 8 GiB virtio block device\n"
success_end:
not_found: .ascii "hackios-disk: cannot read 8 GiB /dev/rrvblk0\n"
not_found_end:
