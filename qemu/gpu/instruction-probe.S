/* Deterministic A64 EL0 loop. Build with clang --target=aarch64-none-elf.
 * The user portion executes exactly 3*ITERATIONS + 4 instructions, including
 * SVC. The loop has direct TB chaining and runs without guest OS/firmware.
 * UART B/D bracket just the workload; WFI keeps counters stable for QMP stop.
 */
#ifndef ITERATIONS
#define ITERATIONS 10000000
#endif
.section .text
.global _start
_start:
    msr daifset, #15
    adr x0, vectors
    msr vbar_el1, x0
    ldr x0, =0x40180000
    mov sp, x0
    ldr x1, =0x09000000
    mov w0, #66
    str w0, [x1]
    adr x0, user
    msr elr_el1, x0
    msr spsr_el1, xzr
    eret

user:
    movz x2, #(ITERATIONS & 65535)
    movk x2, #((ITERATIONS >> 16) & 65535), lsl #16
    mov x0, #0
1:  add x0, x0, #1
    subs x2, x2, #1
    b.ne 1b
#ifdef FAULT_PROBE
    /* Unassigned physical bus address: virt must raise a data abort. The
     * unused add/SVC tail makes a bulk-TB overcount observable. */
    movz x4, #0xdead, lsl #16
    ldr x5, [x4]
    add x0, x0, #1
#endif
    svc #0

.balign 2048
vectors:
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b completed
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124
    b unexpected
    .space 124

completed:
    ldr x1, =0x09000000
    mov w0, #68
    str w0, [x1]
2:  wfi
    b 2b

unexpected:
    ldr x1, =0x09000000
    mov w0, #69
    str w0, [x1]
3:  wfi
    b 3b
