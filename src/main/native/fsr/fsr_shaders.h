// The FSR shaders translated to MSL, embedded by scripts/build-fsr-embedded.sh. Names are the kernel names, with
// "@lowres" for the frame interpolation variants that take motion vectors at render resolution.
#ifndef FSR_SHADERS_H
#define FSR_SHADERS_H

#include <stddef.h>

typedef struct {
	const char *name;
	const char *source;
} M2MFsrShader;

extern const M2MFsrShader m2m_fsr_shaders[];
extern const size_t m2m_fsr_shader_count;

#endif
