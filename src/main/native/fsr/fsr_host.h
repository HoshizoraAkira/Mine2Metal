#pragma once

// What the FSR frame generator (fsr_framegen.mm) and the FSR upscalers (fsr_upscale.mm) share: AMD's host code records
// its passes through ffx_metal.mm, which hands them to M2MFfxHost, so they land in the frame's command buffer like the
// backend's own work, on Metal 3 or Metal 4.

extern "C" {
#include "m2m_context.h"
}

#include <FidelityFX/host/ffx_interface.h>

#include "ffx_metal.h"

@interface M2MFfxHost : NSObject <FfxMetalHost>
@property(nonatomic, weak) M2MContext *ctx;
@end

// The embedded MSL of AMD's passes (and ours, src/main/native/fsr/glsl), by pass name.
NSDictionary<NSString *, NSString *> *m2m_fsr_shader_sources(void);
FfxSurfaceFormat m2m_ffx_format(MTLPixelFormat format);
FfxResource m2m_ffx_resource(id<MTLTexture> texture, uint32_t usage, const wchar_t *name);
