// Force-included when compiling AMD's FidelityFX host code (third_party/fidelityfx-1.1.4), which was written for
// Windows: the MSVC bounds-checked string functions it uses, mapped onto their standard counterparts.
#ifndef FFX_MACOS_COMPAT_H
#define FFX_MACOS_COMPAT_H

#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>

#ifndef FFX_UNUSED
#define FFX_UNUSED(x) ((void)(x))
#endif

#ifndef _countof
#define _countof(array) (sizeof(array) / sizeof((array)[0]))
#endif

#ifdef __cplusplus
#include <cstddef>

static inline int wcscpy_s(wchar_t *destination, size_t capacity, const wchar_t *source) {
	if (capacity == 0) {
		return 1;
	}
	wcsncpy(destination, source, capacity - 1);
	destination[capacity - 1] = L'\0';
	return 0;
}

template <size_t N> static inline int wcscpy_s(wchar_t (&destination)[N], const wchar_t *source) {
	return wcscpy_s(destination, N, source);
}

static inline int swprintf_s(wchar_t *buffer, size_t capacity, const wchar_t *format, ...) {
	va_list arguments;
	va_start(arguments, format);
	int written = vswprintf(buffer, capacity, format, arguments);
	va_end(arguments);
	return written;
}

static inline int sprintf_s(char *buffer, size_t capacity, const char *format, ...) {
	va_list arguments;
	va_start(arguments, format);
	int written = vsnprintf(buffer, capacity, format, arguments);
	va_end(arguments);
	return written;
}
#endif

#endif
