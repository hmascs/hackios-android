/* SPDX-License-Identifier: GPL-2.0-or-later */
/* AArch64 bare-metal transport probe for QEMU virt, not an iOS driver. */
.text
.global _start
_start:
    mov x20, #0x09000000           /* PL011 */
    str wzr, [x20, #0x30]         /* disable before configuring */
    mov w0, #0x70                 /* 8-bit, FIFO */
    str w0, [x20, #0x2c]
    mov w0, #0x301                /* RXE | TXE | UARTEN */
    str w0, [x20, #0x30]

    adr x1, command
    mov w2, #48
    bl send
    adr x1, expected_reply
    mov w2, #16
1:  ldr w3, [x20, #0x18]
    tbnz w3, #4, 1b               /* RXFE */
    ldr w3, [x20]
    ldrb w4, [x1], #1
    and w3, w3, #255
    cmp w3, w4
    b.ne failed
    subs w2, w2, #1
    b.ne 1b
    adr x1, success
    b ack
failed:
    adr x1, failure
ack:
    mov w2, #4
    bl send
2:  wfi
    b 2b

send:
    ldr w3, [x20, #0x18]
    tbnz w3, #5, send             /* TXFF */
    ldrb w3, [x1], #1
    str w3, [x20]
    subs w2, w2, #1
    b.ne send
    ret

.balign 4
command:
    .ascii "HGL1"
    .hword 1, 48
    .float -0.8, -0.8, 0.8, -0.8, 0.0, 0.8
    .float 1.0, 0.0, 1.0, 1.0
expected_reply:
    .ascii "HGR1"
    .word 0                      /* status */
    .byte 255, 0, 255, 255       /* triangle center */
    .byte 0, 0, 0, 255           /* background corner */
success: .ascii "HGOK"
failure: .ascii "HGNO"
.section .note.GNU-stack,"",%progbits
