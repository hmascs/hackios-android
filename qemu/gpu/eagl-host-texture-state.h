/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HIOS_HOST_TEXTURE_STATE_H
#define HIOS_HOST_TEXTURE_STATE_H
#include <stdint.h>
#include <stddef.h>
/* Single host EGL thread. Each worker context is created with no share context,
 * so SLOT denotes a texture namespace, not merely the active texture unit. */
void hios_texture_select_context(uint32_t slot);
void hios_texture_drop_context(uint32_t slot);
void hios_texture_delete(unsigned count, const unsigned *names);
/* 0 means unsupported binding/state capacity, so the caller fails closed.
 * Invalid swizzle enums preserve native GL error behavior without state changes. */
int hios_texture_parameteri(unsigned target, unsigned name, int value);
int hios_texture_parameter_query(unsigned target, unsigned name, int *value);
int hios_texture_is_swizzle(unsigned name);
#endif
